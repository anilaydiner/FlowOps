package com.flowops.application.notification;

import com.flowops.application.event.DomainEventHandler;
import com.flowops.domain.OutboxEvents;
import com.flowops.domain.Request;
import com.flowops.domain.event.RequestRejected;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.port.RequestRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(100)
public class NotifyRequestRejected implements DomainEventHandler<RequestRejected> {
    private final RequestRepository requests;
    private final EmailComposer emails;
    private final OutboxService outbox;

    public NotifyRequestRejected(RequestRepository requests, EmailComposer emails, OutboxService outbox) {
        this.requests = requests;
        this.emails = emails;
        this.outbox = outbox;
    }

    @Override
    public Class<RequestRejected> eventType() {
        return RequestRejected.class;
    }

    @Override
    public void handle(RequestRejected event) {
        Request request = requests.findById(event.requestId())
                .orElseThrow(() -> new NotFoundException("Request not found: " + event.requestId()));
        Long recipientId = request.getRequester().getId();
        EmailComposer.ComposedEmail email = emails.rejected(request, event.rejectorUsername(), event.justification());
        outbox.notifyFeed(event.requestId(), recipientId, OutboxEvents.NOTIFY_REJECT,
                email.subject(), email.text(), null);
        outbox.notifyEmail(event.requestId(), recipientId, OutboxEvents.REJECTED,
                email.subject(), email.text(), email.html(), null);
    }
}
