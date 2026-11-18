package com.flowops.application.execution;

import com.flowops.application.audit.LifecycleAuditRecorder;
import com.flowops.application.event.DomainEventPublisher;
import com.flowops.domain.execution.port.ExecutionPlanRepository;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.request.*;
import com.flowops.domain.request.event.RequestExecuted;
import com.flowops.domain.request.port.RequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Completion is a proof against the immutable expected action set, including a deliberate empty set. */
@Service
public class CompleteRequestExecution {
    private final ExecutionPlanRepository plans;
    private final RequestRepository requests;
    private final LifecycleAuditRecorder audit;
    private final DomainEventPublisher events;
    private final java.time.Clock clock;
    public CompleteRequestExecution(ExecutionPlanRepository plans,RequestRepository requests,LifecycleAuditRecorder audit,DomainEventPublisher events,java.time.Clock clock){this.plans=plans;this.requests=requests;this.audit=audit;this.events=events;this.clock=clock;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void completeLocked(Request request,CallerIdentity caller){
        if(request.getStatus()!=RequestStatus.APPROVED || request.getExecutionPlanId()==null || request.hasUnresolvedExecutionHistory())return;
        var plan=plans.findByRequestId(request.getId()).orElse(null);
        if (plan == null || !plan.id().equals(request.getExecutionPlanId())) return;
        var proof = plans.completionOf(plan).orElse(null);
        if (proof == null) return;
        var before = LifecycleAuditRecorder.state(request);
        request.completeExecution(proof, clock.instant());
        requests.save(request);
        audit.transition(request,caller,before,plan.actions().isEmpty()?"Approved plan deliberately requires no actions":"Every frozen planned action completed");
        audit.record(request,caller,"EXECUTION","COMPLETED","execution-plan:"+plan.id()+":completed","Execution plan completed",before,
                LifecycleAuditRecorder.state(request),java.util.Map.of("executionPlanId",plan.id().toString(),"completedActions",plan.actions().size()),null);
        events.publish(new RequestExecuted(request.getId(),plan.id(),plan.actions().size()));
    }
}
