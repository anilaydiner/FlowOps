package com.flowops.application.execution;

import com.flowops.application.audit.LifecycleAuditRecorder;
import com.flowops.application.execution.model.ExecutionPlanRecovery.Issue;
import com.flowops.application.execution.port.ExecutionPlanReconciliationRepository;
import com.flowops.application.request.port.RequestWriteLock;
import com.flowops.domain.execution.port.ExecutionPlanRepository;
import com.flowops.domain.request.*;
import com.flowops.domain.request.port.RequestRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ReconcileExecutionPlan {
    public enum Outcome { SKIPPED, BUSY, UNCHANGED, REPAIRED, COMPLETED, ATTENTION }
    public record Result(Outcome outcome,int created,int restored,int rebound,List<Issue> issues) {}
    private final RequestRepository requests;
    private final RequestWriteLock requestWriteLock;
    private final ExecutionPlanRepository plans;
    private final ExecutionPlanReconciliationRepository evidence;
    private final RecoverFrozenPlanJobs recovery;
    private final CompleteRequestExecution completion;
    private final LifecycleAuditRecorder audit;
    public ReconcileExecutionPlan(RequestRepository requests,RequestWriteLock requestWriteLock,ExecutionPlanRepository plans,ExecutionPlanReconciliationRepository evidence,
                                  RecoverFrozenPlanJobs recovery,CompleteRequestExecution completion,LifecycleAuditRecorder audit){
        this.requests=requests;
        this.requestWriteLock = requestWriteLock;
        this.plans=plans;this.evidence=evidence;this.recovery=recovery;this.completion=completion;this.audit=audit;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Result process(Long requestId){
        var request=requestWriteLock.tryFindByIdForUpdate(requestId).orElse(null);
        if(request==null)return new Result(Outcome.BUSY,0,0,0,List.of());
        if(request.getStatus()!=RequestStatus.APPROVED || request.getExecutionPlanId()==null || request.hasUnresolvedExecutionHistory())return new Result(Outcome.SKIPPED,0,0,0,List.of());
        var plan=plans.findByRequestId(requestId).orElse(null);
        if(plan==null)return attention(request,List.of(new Issue(0,"PLAN","MANIFEST_MISSING",null)));
        var repaired=recovery.recoverLocked(request,plan,RecoverFrozenPlanJobs.Mode.EXISTING_PLAN,"Periodic frozen-plan reconciliation");
        if(!repaired.issues().isEmpty())return attention(request,repaired.issues());
        if(repaired.changed()){
            var before=LifecycleAuditRecorder.state(request);request.recordActivity();requests.save(request);
            audit.record(request,null,"EXECUTION_PLAN","RECOVERED","plan-recovered:"+java.util.UUID.randomUUID(),"Frozen execution plan materialization recovered",before,
                    LifecycleAuditRecorder.state(request),Map.of("executionPlanId",plan.id().toString(),"created",repaired.created(),"restored",repaired.restored(),"rebound",repaired.rebound()),null);
        }
        completion.completeLocked(request,null);
        return new Result(request.getStatus()==RequestStatus.EXECUTED?Outcome.COMPLETED:repaired.changed()?Outcome.REPAIRED:Outcome.UNCHANGED,
                repaired.created(),repaired.restored(),repaired.rebound(),List.of());
    }
    private Result attention(Request request,List<Issue> issues){
        var fresh=new ArrayList<Issue>();
        for(var issue:issues){String suffix=key(request,issue);if(!evidence.auditRecorded(request.getId(),suffix))fresh.add(issue);}
        if(!fresh.isEmpty()){
            request.recordActivity();requests.save(request);
            for(var issue:fresh){
                var details=new LinkedHashMap<String,Object>();details.put("executionPlanId",request.getExecutionPlanId().toString());
                details.put("position",issue.position());details.put("action",issue.action());details.put("reasonCode",issue.code());if(issue.jobId()!=null)details.put("jobId",issue.jobId());
                audit.record(request,null,"EXECUTION_PLAN","RECONCILIATION_REQUIRED",key(request,issue),"Frozen plan needs operator review",Map.of(),
                        Map.of("requiresAttention",true),details,"Execution evidence does not authorize automatic job recreation");
            }
        }
        return new Result(Outcome.ATTENTION,0,0,0,List.copyOf(issues));
    }
    private String key(Request request,Issue issue){return "plan-reconciliation:"+request.getExecutionPlanId()+":"+issue.position()+":"+issue.code()+":"+(issue.jobId()==null?0:issue.jobId());}
}
