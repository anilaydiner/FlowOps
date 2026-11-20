package com.flowops.application.notification;

import com.flowops.application.event.DomainEventHandler;
import com.flowops.application.notification.outbox.*;
import com.flowops.application.request.port.RequestWriteLock;
import com.flowops.domain.exception.*;
import com.flowops.domain.execution.port.ExecutionPlanRepository;
import com.flowops.domain.request.RequestStatus;
import com.flowops.domain.request.event.RequestExecuted;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(100)
public class NotifyRequestExecuted implements DomainEventHandler<RequestExecuted> {

    private final RequestWriteLock requestWriteLock;

    private final ExecutionPlanRepository plans;

    private final EmailComposer emails;

    private final OutboxService outbox;

    public NotifyRequestExecuted(RequestWriteLock requestWriteLock, ExecutionPlanRepository plans, EmailComposer emails,
            OutboxService outbox) {
        this.requestWriteLock = requestWriteLock;
        this.plans = plans;
        this.emails = emails;
        this.outbox = outbox;
    }

    @Override
    public Class<RequestExecuted> eventType() {
        return RequestExecuted.class;
    }

    @Override
    public void handle(RequestExecuted event) {
        var request = requestWriteLock.findByIdForUpdate(event.requestId())
            .orElseThrow(() -> new NotFoundException("Request not found"));
        var plan = plans.findByRequestId(event.requestId())
            .orElseThrow(() -> new ConflictException("Completion requires a frozen plan"));
        if (!plan.id().equals(event.executionPlanId()) || !plan.id().equals(request.getExecutionPlanId())
                || event.completedActions() != plan.actions().size()
                || (request.getStatus() != RequestStatus.EXECUTED && request.getStatus() != RequestStatus.CLOSED)
                || !plans.allCompleted(plan.id()))
            throw new ConflictException("execution-plan-mismatch",
                    "Completion event does not match a completed frozen plan");
        var email = emails.executed(request, plan.actions().size());
        var owner = request.getRequester().getId();
        outbox.notifyFeed(request.getId(), owner, OutboxEvents.NOTIFY_EXECUTED, email.subject(), email.text(), null);
        outbox.notifyEmail(request.getId(), owner, OutboxEvents.EXECUTED, email.subject(), email.text(), email.html(),
                null);
    }

}
