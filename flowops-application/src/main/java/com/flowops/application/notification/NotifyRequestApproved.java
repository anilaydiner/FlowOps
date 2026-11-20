package com.flowops.application.notification;

import com.flowops.application.event.DomainEventHandler;
import com.flowops.application.notification.outbox.OutboxEvents;
import com.flowops.application.notification.outbox.OutboxService;
import com.flowops.application.request.port.RequestWriteLock;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.request.Request;
import com.flowops.domain.request.event.RequestApproved;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(100)
public class NotifyRequestApproved implements DomainEventHandler<RequestApproved> {

    private final RequestWriteLock requestWriteLock;

    private final EmailComposer emails;

    private final OutboxService outbox;

    private final com.flowops.domain.execution.port.ExecutionPlanRepository plans;

    public NotifyRequestApproved(RequestWriteLock requestWriteLock, EmailComposer emails, OutboxService outbox,
            com.flowops.domain.execution.port.ExecutionPlanRepository plans) {
        this.requestWriteLock = requestWriteLock;
        this.plans = plans;
        this.emails = emails;
        this.outbox = outbox;
    }

    @Override
    public Class<RequestApproved> eventType() {
        return RequestApproved.class;
    }

    @Override
    public void handle(RequestApproved event) {
        Request request = requestWriteLock.findByIdForUpdate(event.requestId())
            .orElseThrow(() -> new NotFoundException("Request not found: " + event.requestId()));
        var plan = plans.findByRequestId(request.getId())
            .orElseThrow(() -> new com.flowops.domain.exception.ConflictException("Approval has no frozen plan"));
        if (!plan.id().equals(event.executionPlanId()) || !plan.id().equals(request.getExecutionPlanId())
                || !plan.approval().equals(event.approval()))
            throw new com.flowops.domain.exception.ConflictException("approval-event-mismatch",
                    "Approval event differs from its frozen fact");
        Long requesterId = request.getRequester().getId();
        EmailComposer.ComposedEmail email = emails.approved(request, plan.approval(), plan.actions().size());
        outbox.notifyFeed(event.requestId(), requesterId, OutboxEvents.NOTIFY_APPROVE, email.subject(), email.text(),
                null);
        outbox.notifyEmail(event.requestId(), requesterId, OutboxEvents.APPROVED, email.subject(), email.text(),
                email.html(), null);
    }

}
