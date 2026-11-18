package com.flowops.application.execution;

import com.flowops.application.audit.LifecycleAuditRecorder;
import com.flowops.application.execution.model.ExecutionPlanRecovery.*;
import com.flowops.application.execution.port.ExecutionPlanReconciliationRepository;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.execution.*;
import com.flowops.domain.execution.port.*;
import com.flowops.domain.request.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Shared initial/replay/periodic materialization. Caller owns the parent lock and transaction. */
@Service
public class RecoverFrozenPlanJobs {
    public enum Mode { NEW_PLAN, EXISTING_PLAN }
    private final ExecutionJobRepository jobs;
    private final ExecutionPlanRepository plans;
    private final ExecutionPlanReconciliationRepository evidence;
    private final LifecycleAuditRecorder audit;
    public RecoverFrozenPlanJobs(ExecutionJobRepository jobs,ExecutionPlanRepository plans,ExecutionPlanReconciliationRepository evidence,LifecycleAuditRecorder audit){
        this.jobs=jobs;this.plans=plans;this.evidence=evidence;this.audit=audit;
    }
    private record Work(ExecutionPlan.Action action,Materialization receipt,ExecutionJob existing,Long binding,boolean restoreRow,boolean bind) {}
    @Transactional(propagation=Propagation.MANDATORY)
    public Result recoverLocked(Request request,ExecutionPlan plan,Mode mode,String reason){
        var issues=new ArrayList<Issue>();
        if(!plan.id().equals(request.getExecutionPlanId()) || !evidence.manifestValid(plan.id(),request.getId()))
            return new Result(0,0,0,List.of(new Issue(0,"PLAN","MANIFEST_INCOMPLETE",null)));
        var rows=jobs.findAllForLockedRequest(request.getId());
        var byKey=new HashMap<String,ExecutionJob>();for(var row:rows)byKey.put(row.getIdempotencyKey(),row);
        var expectedKeys=plan.actions().stream().map(ExecutionPlan.Action::idempotencyKey).collect(java.util.stream.Collectors.toSet());
        for(var row:rows)if(!expectedKeys.contains(row.getIdempotencyKey()))issues.add(new Issue(0,row.getAction(),"UNEXPECTED_JOB",row.getId()));
        var bindings=evidence.bindings(plan.id());var receipts=evidence.materializations(plan.id());var work=new ArrayList<Work>();
        for(var action:plan.actions()){
            var receipt=receipts.get(action.action());Long binding=bindings.get(action.action());
            var row=byKey.get(action.idempotencyKey());
            if(row==null)row=jobs.findByIdempotencyKey(action.idempotencyKey()).orElse(null);
            String problem=null;
            if(row!=null){
                if(!evidence.actionMatches(plan.id(),action.action(),row.getId()))problem="JOB_IDENTITY_MISMATCH";
                else if(binding!=null && !binding.equals(row.getId()))problem="BINDING_IDENTITY_MISMATCH";
                else if(receipt==null || !Objects.equals(receipt.jobId(),row.getId()) || !Objects.equals(receipt.createdAt(),row.getCreatedAt()))problem="UNVERIFIABLE_MATERIALIZATION";
                else if(binding==null && row.getStatus()==JobStatus.PLANNED && evidence.executionHistory(request.getId(),action.action(),action.idempotencyKey(),row.getId()))problem="EXECUTION_HISTORY_CONFLICT";
                else if(binding==null && row.getStatus()==JobStatus.COMPLETED && !evidence.completionRecorded(row.getId()))problem="COMPLETION_EVIDENCE_MISSING";
                if(problem==null)work.add(new Work(action,receipt,row,binding,false,binding==null));
            }else if(receipt!=null){
                if(receipt.jobId()==null || receipt.createdAt()==null || receipt.maxRetries()==null || !receipt.unstartedAtCapture())problem="UNVERIFIABLE_MATERIALIZATION";
                else if(binding!=null && !binding.equals(receipt.jobId()))problem="BINDING_IDENTITY_MISMATCH";
                else if(jobs.findById(receipt.jobId()).isPresent())problem="JOB_IDENTITY_COLLISION";
                else if(evidence.executionHistory(request.getId(),action.action(),action.idempotencyKey(),receipt.jobId()))problem="MISSING_JOB_HAS_EXECUTION_HISTORY";
                if(problem==null)work.add(new Work(action,receipt,null,binding,true,binding==null));
            }else if(mode!=Mode.NEW_PLAN || binding!=null || evidence.anyMaterializationHistory(request.getId(),action.action(),action.idempotencyKey()))problem="UNVERIFIABLE_MATERIALIZATION";
            else work.add(new Work(action,null,null,null,false,true));
            if(problem!=null)issues.add(new Issue(action.position(),action.action(),problem,receipt==null?binding:receipt.jobId()));
        }
        boolean changes=work.stream().anyMatch(w->w.existing()==null || w.bind());
        if(changes && (request.getStatus()!=RequestStatus.APPROVED || request.hasUnresolvedExecutionHistory()))issues.add(new Issue(0,"PLAN","REQUEST_STOPPED_OR_UNKNOWN",null));
        // Inspect the entire expected set before changing any job; unsafe state is not silently repaired around.
        if(!issues.isEmpty())return new Result(0,0,0,issues);
        int created=0,restored=0,rebound=0;
        for(var w:work){
            var action=w.action();var row=w.existing();
            if(row==null){
                if(w.restoreRow()){
                    var original=w.receipt();row=ExecutionJob.restoredPlanned(original.jobId(),action.idempotencyKey(),request.getId(),action.action(),action.correlationId(),action.input(),original.createdAt(),original.maxRetries());
                    if(!jobs.restoreUnstarted(row))throw new ConflictException("execution-plan-recovery-conflict","Recorded job identity was concurrently occupied");restored++;
                }else{
                    row=new ExecutionJob(action.idempotencyKey(),request.getId(),action.action(),action.correlationId());row.freezeInput(action.input());
                    row=jobs.saveIfAbsent(row).orElseThrow(()->new ConflictException("execution-plan-recovery-conflict","Frozen action key was concurrently occupied"));created++;
                }
            }
            if(w.bind()){plans.bindJob(plan.id(),action.action(),row.getId());if(w.existing()!=null)rebound++;}
            if(w.existing()==null || w.bind()){
                String event=w.restoreRow()?"RESTORED_FROM_PLAN":w.existing()==null?"PLANNED":"BINDING_RESTORED";
                String suffix=event.equals("PLANNED")?"job:"+row.getId()+":planned":"plan-recovery:"+UUID.randomUUID();
                String summary=event.equals("PLANNED")?"Execution job "+action.action()+" enqueued":
                        "Frozen execution plan "+action.action()+" "+event;
                audit.record(request,null,event.equals("BINDING_RESTORED")?"EXECUTION_PLAN":"JOB",event,suffix,
                        summary,Map.of(),Map.of("status",row.getStatus().name()),
                        Map.of("jobId",row.getId(),"action",action.action(),"executionPlanId",plan.id().toString(),"position",action.position()),reason);
            }
        }
        if(!plans.jobsMatch(plan.id()))throw new ConflictException("execution-plan-mismatch","Recovered jobs do not exactly match the frozen manifest");
        return new Result(created,restored,rebound,List.of());
    }
}
