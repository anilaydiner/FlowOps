package com.flowops.application.request.lifecycle;

import com.flowops.application.request.port.ApprovalStore;
import com.flowops.application.audit.LifecycleAuditRecorder;
import com.flowops.application.event.DomainEventPublisher;
import com.flowops.application.execution.*;
import com.flowops.application.request.port.RequestWriteLock;
import com.flowops.domain.exception.*;
import com.flowops.domain.execution.*;
import com.flowops.domain.execution.port.*;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.request.*;
import com.flowops.domain.request.event.RequestApproved;
import com.flowops.domain.request.port.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** One approval boundary for human review and all-skipped submission. Caller already owns the request transaction. */
@Service
public class FinalizeApproval {
    private final RequestRepository requests;
    private final RequestWriteLock requestWriteLock;
    private final StepInstanceRepository steps;
    private final DecisionRepository decisions;
    private final ExecutionPlanRepository plans;
    private final ExecutionJobRepository jobs;
    private final ExecutionService execution;
    private final CompleteRequestExecution completion;
    private final LifecycleAuditRecorder audit;
    private final DomainEventPublisher events;
    private final Clock clock;
    private final ApprovalStore approvals;
    public FinalizeApproval(RequestRepository requests,RequestWriteLock requestWriteLock,StepInstanceRepository steps,DecisionRepository decisions,ExecutionPlanRepository plans,ExecutionJobRepository jobs,
            ExecutionService execution,CompleteRequestExecution completion,LifecycleAuditRecorder audit,DomainEventPublisher events,Clock clock,ApprovalStore approvals){
        this.requests=requests;
        this.requestWriteLock = requestWriteLock;
        this.steps=steps;this.decisions=decisions;this.plans=plans;this.jobs=jobs;this.execution=execution;this.completion=completion;this.audit=audit;this.events=events;this.clock=clock;this.approvals=approvals;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public Request execute(Long requestId,ApprovalEvidence evidence){
        if(evidence.trigger()==ApprovalEvidence.Trigger.LEGACY_PLAN_DISPOSITION)
            throw new ConflictException("legacy-plan-state","Historical planning uses the reviewed disposition operation, not approval finalization");
        var request=requestWriteLock.findByIdForUpdate(requestId).orElseThrow(()->new NotFoundException("Request not found: "+requestId));
        var existing=plans.findByRequestId(requestId).orElse(null);
        if(existing!=null){
            if(!existing.id().equals(request.getExecutionPlanId()) || !existing.approval().equals(evidence))
                throw new ConflictException("approval-evidence-mismatch","Approval already has different frozen evidence");
            return request;
        }
        var stepsInActivationOrder = steps.findByRequestIdOrderById(requestId);
        var votes = evidence.trigger() == ApprovalEvidence.Trigger.FINAL_HUMAN_APPROVAL
                ? decisions.findByStepInstanceIdOrderById(evidence.stepInstanceId()) : List.<Decision>of();
        if(jobs.countByRequestId(requestId)!=0)throw new ConflictException("Existing execution work requires explicit legacy disposition");
        var configuration=plans.configuration(request.getType().getId()).orElseThrow(()->new ConflictException("Request type configuration is unavailable"));
        var configured=configuration.actions();
        if(configured==null)throw new ConflictException("Execution actions are unknown, not an empty plan");
        var actions=new ArrayList<ExecutionPlan.Action>();
        for(String action:new LinkedHashSet<>(configured))actions.add(new ExecutionPlan.Action(actions.size()+1,action,"req:"+requestId+":"+action,request.getPayload(),request.getCorrelationId()));
        var before = LifecycleAuditRecorder.state(request);
        var now = clock.instant();
        var plan = new ExecutionPlan(UUID.randomUUID(), requestId, configuration.typeId(), configuration.typeCode(),
                configuration.updatedAt(), now, evidence, actions);
        request.approve(plan.approvalPlan(), stepsInActivationOrder, votes);
        approvals.saveApproval(request, plan);
        // Materialize before fan-out, so every subscriber observes the recorded complete job set.
        execution.materializeNewPlan(request);
        request=requestWriteLock.findByIdForUpdate(requestId).orElseThrow();
        CallerIdentity actor=evidence.actorKind()==ApprovalEvidence.ActorKind.HUMAN?new CallerIdentity(evidence.actorId(),evidence.actorName()):null;
        audit.transition(request,actor,before,evidence.trigger().name());
        audit.record(request,actor,"APPROVAL","FINALIZED","approval:"+plan.id(),"Approval finalized with a frozen execution plan",before,
                LifecycleAuditRecorder.state(request),Map.of("executionPlanId",plan.id().toString(),"expectedActions",actions.size(),"approval",evidence.snapshot()),evidence.trigger().name());
        events.publish(new RequestApproved(requestId,plan.id(),evidence));
        request=requestWriteLock.findByIdForUpdate(requestId).orElseThrow();completion.completeLocked(request,actor);
        return requests.findById(requestId).orElseThrow();
    }
}
