package com.flowops.application.notification;

import com.flowops.application.event.DomainEventHandler;
import com.flowops.application.notification.outbox.OutboxEvents;
import com.flowops.application.notification.outbox.OutboxService;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.request.Request;
import com.flowops.domain.request.event.RequestApproved;
import com.flowops.domain.request.port.RequestRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(100)
public class NotifyRequestApproved implements DomainEventHandler<RequestApproved> {
    private final RequestRepository requests;
    private final EmailComposer emails;
    private final OutboxService outbox;

    public NotifyRequestApproved(RequestRepository requests, EmailComposer emails, OutboxService outbox) {
        this.requests = requests;
        this.emails = emails;
        this.outbox = outbox;
    }

    @Override
    public Class<RequestApproved> eventType() {
        return RequestApproved.class;
    }

    @Override
    public void handle(RequestApproved event) {
        Request request = requests.findById(event.requestId())
                .orElseThrow(() -> new NotFoundException("Request not found: " + event.requestId()));
        Long requesterId = request.getRequester().getId();
        EmailComposer.ComposedEmail email = emails.approved(request, event.approverUsername());
        outbox.notifyFeed(event.requestId(), requesterId, OutboxEvents.NOTIFY_APPROVE,
                email.subject(), email.text(), null);
        outbox.notifyEmail(event.requestId(), requesterId, OutboxEvents.APPROVED,
                email.subject(), email.text(), email.html(), null);
    }
}
