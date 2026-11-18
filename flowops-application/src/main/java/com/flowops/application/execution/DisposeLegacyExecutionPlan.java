package com.flowops.application.execution;

import com.flowops.application.request.RequestTargets;
import com.flowops.application.audit.LifecycleAuditRecorder;
import com.flowops.application.execution.model.LegacyPlanDisposition.*;
import com.flowops.application.execution.port.*;
import com.flowops.domain.exception.*;
import com.flowops.domain.execution.*;
import com.flowops.domain.execution.port.*;
import com.flowops.domain.identity.*;
import com.flowops.domain.identity.policy.*;
import com.flowops.domain.identity.port.UserRepository;
import com.flowops.domain.request.*;
import com.flowops.domain.request.port.RequestRepository;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** One reviewed manifest transaction. No provider calls, historical input edits or automatic retries. */
@Service
public class DisposeLegacyExecutionPlan {
    private final RequestRepository requests;
    private final ExecutionJobRepository jobs;
    private final ExecutionPlanRepository plans;
    private final LegacyPlanDispositionRepository records;
    private final ExecutionRecoveryRepository recovery;
    private final ExecutionRecoveryTransactions access;
    private final ProviderContracts contracts;
    private final UserRepository users;
    private final AuthorizationEvaluator authz;
    private final ExecutionAttemptRecorder attempts;
    private final LifecycleAuditRecorder audit;
    private final CompleteRequestExecution completion;
    private final Clock clock;
    public DisposeLegacyExecutionPlan(RequestRepository requests,ExecutionJobRepository jobs,ExecutionPlanRepository plans,
            LegacyPlanDispositionRepository records,ExecutionRecoveryRepository recovery,ExecutionRecoveryTransactions access,
            ProviderContracts contracts,UserRepository users,AuthorizationEvaluator authz,ExecutionAttemptRecorder attempts,
            LifecycleAuditRecorder audit,CompleteRequestExecution completion,Clock clock){
        this.requests=requests;this.jobs=jobs;this.plans=plans;this.records=records;this.recovery=recovery;this.access=access;
        this.contracts=contracts;this.users=users;this.authz=authz;this.attempts=attempts;this.audit=audit;this.completion=completion;this.clock=clock;
    }
    private record Work(ProposedAction proposal,ExecutionJob existing,ExecutionPlan.Action action){}
    @Transactional
    public Result dispose(Long requestId,Input input,CallerIdentity caller){
        validate(input);
        var request=requests.findByIdForUpdate(requestId).orElseThrow(()->new NotFoundException("Request not found: "+requestId));
        var operator=users.findById(caller.id()).orElseThrow(()->new ForbiddenException("Inactive execution operator"));
        if(!operator.isActive() || !authz.evaluate(operator,PermissionCodes.EXECUTION_PLAN_DISPOSE,RequestTargets.from(request,null)).allowed())
            throw new ForbiddenException("Active scoped execution:plan:dispose permission and separation of duties are required");
        var actor=new CallerIdentity(operator.getId(),operator.getUsername());var payload=payload(input);
        var prior=records.find(input.commandId()).orElse(null);
        if(prior!=null)return replay(request,input,actor,prior,payload);
        if(!input.expectedVersion().equals(request.getVersion()))throw conflict("stale-plan-disposition","Reload the request before disposing its legacy plan");
        if(request.getStatus()!=RequestStatus.APPROVED || request.getExecutionPlanId()!=null || plans.findByRequestId(requestId).isPresent())
            throw conflict("legacy-plan-state","Disposition requires an approved request without a frozen manifest");
        var now=clock.instant();var before=LifecycleAuditRecorder.state(request);
        var rows=jobs.findAllForLockedRequest(requestId);var byId=new LinkedHashMap<Long,ExecutionJob>();rows.forEach(j->byId.put(j.getId(),j));
        var work=new ArrayList<Work>();
        if(input.mode()==Mode.ADOPT){
            if(records.missingHistoricalJobs(requestId))throw conflict("legacy-job-missing","Historical job identities are missing; restore original data or keep the request quarantined");
            var used=new HashSet<Long>();
            for(var proposal:input.actions()){
                var job=proposal.existingJobId()==null?null:byId.get(proposal.existingJobId());
                if(proposal.existingJobId()!=null && (job==null || !used.add(job.getId())))throw conflict("legacy-job-identity","Each existing job must be identified once on its own request");
                if(job!=null){
                    if(!job.getAction().equals(proposal.action()) || proposal.idempotencyKey()!=null || proposal.input()!=null)
                        throw conflict("legacy-job-identity","Existing actions retain their stored key and input; replacement values are not accepted");
                    if(job.getInputPayload()==null)throw conflict("legacy-job-input-unknown","Historical input is missing; current request payload cannot replace it");
                    if(job.getStatus()==JobStatus.CANCELLED)throw conflict("legacy-job-state","Cancelled work cannot be adopted into an executable approval");
                    if(job.getStatus()==JobStatus.STARTED && job.getLeaseExpiresAt()!=null && now.isBefore(job.getLeaseExpiresAt()))throw conflict("execution-attempt-active","Drain or reconcile the live claim before adopting its plan");
                    recovery.latest(job.getId()).ifPresent(e->{if(e.observation()==null && now.isBefore(e.command().expiresAt()))throw conflict("reconciliation-in-progress","Wait for the pending provider lookup before adopting its plan");});
                    if(job.getStatus()==JobStatus.COMPLETED && !records.completedEvidence(job.getId()))throw conflict("legacy-completion-unverified","A completed row needs retained immutable outcome evidence");
                }else if(records.actionHistory(requestId,proposal.action(),proposal.idempotencyKey()))throw conflict("legacy-action-history","Missing work cannot replace an existing key or historical action");
                if(job==null || job.getStatus()!=JobStatus.COMPLETED){
                    var contract=job==null?contracts.require(proposal.action()):contracts.require(job);
                    if(!contract.providerId().equals(proposal.providerId()))throw conflict("legacy-provider-identity","The reviewed original provider must match the configured recovery route");
                    if(job!=null && !records.providerHistoryMatches(job.getId(),proposal.providerId()))throw conflict("legacy-provider-identity","The reviewed provider conflicts with retained attempt or lookup history");
                }
                var action=new ExecutionPlan.Action(work.size()+1,proposal.action(),job==null?proposal.idempotencyKey():job.getIdempotencyKey(),
                        job==null?proposal.input():job.getInputPayload(),job==null?request.getCorrelationId():job.getCorrelationId());
                work.add(new Work(proposal,job,action));
            }
            if(used.size()!=rows.size())throw conflict("legacy-plan-incomplete","The reviewed manifest must include every existing job");
        }
        UUID planId=input.mode()==Mode.ADOPT?UUID.randomUUID():null;
        var entry=new Entry(input.commandId(),requestId,planId,actor.id(),actor.username(),input.expectedVersion(),input.mode(),input.reason(),input.evidenceReference(),now,payload);
        if(!records.append(entry))return replay(request,input,actor,records.find(input.commandId()).orElseThrow(),payload);
        if(input.mode()==Mode.ADOPT){
            var plannedAt=request.getExecutionPlannedAt()==null?now:request.getExecutionPlannedAt();
            var plan=new ExecutionPlan(planId,requestId,request.getType().getId(),request.getType().getCode(),null,plannedAt,
                    ApprovalEvidence.legacyPlan(actor.id(),actor.username()),work.stream().map(Work::action).toList());
            plans.save(plan);
            for(var item:work){
                var job=item.existing();var old=job==null?null:job.getStatus();
                if(job==null){
                    var action=item.action();job=new ExecutionJob(action.idempotencyKey(),requestId,action.action(),action.correlationId());job.freezeInput(action.input());
                    job.holdForLegacyPlan("Reviewed legacy action requires provider reconciliation",now);
                    job=jobs.saveIfAbsent(job).orElseThrow(()->conflict("legacy-job-conflict","Reviewed key was concurrently occupied"));
                    jobs.save(job); // The insert-only repository allocates a default PLANNED row; persist the hold before commit.
                }else if(old!=JobStatus.COMPLETED && old!=JobStatus.RECONCILIATION_REQUIRED){
                    job.holdForLegacyPlan("Reviewed legacy action requires provider reconciliation",now);jobs.save(job);
                }
                plans.bindJob(planId,item.action().action(),job.getId());
                if(job.getStatus()!=JobStatus.COMPLETED)records.bindProvider(entry.commandId(),job.getId(),item.proposal().providerId());
                if(old!=job.getStatus()){
                    attempts.held(job,old==null?JobStatus.PLANNED:old,"Legacy plan adopted without replay authorization");
                    audit.record(request,actor,"JOB","LEGACY_PLAN_HELD","legacy-plan:"+entry.commandId()+":job:"+job.getId(),"Legacy execution work held for provider reconciliation",
                            old==null?Map.of():Map.of("status",old.name()),Map.of("status",job.getStatus().name()),Map.of("jobId",job.getId(),"action",job.getAction(),"commandId",entry.commandId().toString()),input.reason());
                }
            }
            request.adoptLegacyExecutionPlan(planId,entry.commandId(),plannedAt);
        }else request.recordActivity();
        requests.save(request);
        var details=new LinkedHashMap<String,Object>();details.put("commandId",entry.commandId().toString());details.put("mode",entry.mode().name());details.put("evidenceReference",entry.evidenceReference());
        details.put("expectedActions",work.size());details.put("originalApprovalAttribution","UNKNOWN");if(planId!=null)details.put("executionPlanId",planId.toString());
        audit.record(request,actor,"EXECUTION_PLAN",input.mode()==Mode.ADOPT?"LEGACY_ADOPTED":"LEGACY_QUARANTINED","legacy-plan:"+entry.commandId(),
                input.mode()==Mode.ADOPT?"Reviewed historical execution plan adopted":"Historical execution remains quarantined",before,LifecycleAuditRecorder.state(request),details,input.reason());
        if(input.mode()==Mode.ADOPT){
            if(!plans.jobsMatch(planId))throw conflict("execution-plan-mismatch","Adopted work does not exactly match the reviewed manifest");
            completion.completeLocked(request,actor);
        }
        return new Result(request.getVersion(),Receipt.from(records.find(entry.commandId()).orElseThrow()));
    }
    @Transactional(readOnly=true)
    public List<Receipt> history(Long requestId,int limit,CallerIdentity caller){
        if(limit<1 || limit>100)throw new RequestValidationException(Map.of("limit","Use a limit from 1 to 100"));
        var request=requests.findById(requestId).orElseThrow(()->new NotFoundException("Request not found: "+requestId));access.require(request,caller,PermissionCodes.EXECUTION_READ);
        return records.history(requestId,limit).stream().map(Receipt::from).toList();
    }
    private Result replay(Request request,Input input,CallerIdentity actor,Entry old,Map<String,Object> payload){
        if(!old.requestId().equals(request.getId()) || !old.actorId().equals(actor.id()) || !records.payloadMatches(old.commandId(),payload))
            throw conflict("plan-disposition-command-conflict","Command id was already used with different arguments");
        return new Result(request.getVersion(),Receipt.from(old));
    }
    private void validate(Input i){
        if(i==null || i.commandId()==null || i.expectedVersion()==null || i.expectedVersion()<0 || i.mode()==null || !text(i.reason(),1000) || !text(i.evidenceReference(),1000) || i.actions()==null || i.actions().size()>100)
            throw new RequestValidationException(Map.of("disposition","commandId, expectedVersion, mode, reason, evidenceReference and an explicit action list (0–100) are required"));
        if(i.mode()==Mode.KEEP_QUARANTINED && !i.actions().isEmpty())throw new RequestValidationException(Map.of("actions","Quarantine cannot declare executable work"));
        var actions=new HashSet<String>();var keys=new HashSet<String>();
        for(var a:i.actions()){
            if(a==null || !text(a.action(),64) || !actions.add(a.action()) || (a.existingJobId()!=null && a.existingJobId()<1)
                    || (a.providerId()!=null && !text(a.providerId(),100))
                    || (a.existingJobId()==null && (!text(a.idempotencyKey(),256) || a.input()==null || !keys.add(a.idempotencyKey()) || !text(a.providerId(),100))))
                throw new RequestValidationException(Map.of("actions","Use distinct action codes; new work requires an explicit original stable key, input and provider"));
        }
    }
    private boolean text(String value,int max){return value!=null && !value.isBlank() && value.length()<=max;}
    private Map<String,Object> payload(Input i){
        var result=new LinkedHashMap<String,Object>();result.put("expectedVersion",i.expectedVersion());result.put("mode",i.mode().name());result.put("reason",i.reason());result.put("evidenceReference",i.evidenceReference());
        result.put("actions",i.actions().stream().map(a->{var m=new LinkedHashMap<String,Object>();m.put("action",a.action());m.put("existingJobId",a.existingJobId());m.put("idempotencyKey",a.idempotencyKey());m.put("input",a.input());m.put("providerId",a.providerId());return m;}).toList());return result;
    }
    private ConflictException conflict(String code,String message){return new ConflictException(code,message);}
}
