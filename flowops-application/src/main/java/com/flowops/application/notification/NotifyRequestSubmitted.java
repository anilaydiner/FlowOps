package com.flowops.application.notification;

import com.flowops.application.event.DomainEventHandler;
import com.flowops.application.notification.outbox.OutboxEvents;
import com.flowops.application.notification.outbox.OutboxService;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.request.Request;
import com.flowops.domain.request.event.RequestSubmitted;
import com.flowops.domain.request.port.RequestRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(100)
public class NotifyRequestSubmitted implements DomainEventHandler<RequestSubmitted> {
    private final RequestRepository requests;
    private final EmailComposer emails;
    private final OutboxService outbox;

    public NotifyRequestSubmitted(RequestRepository requests, EmailComposer emails, OutboxService outbox) {
        this.requests = requests;
        this.emails = emails;
        this.outbox = outbox;
    }

    @Override
    public Class<RequestSubmitted> eventType() {
        return RequestSubmitted.class;
    }

    @Override
    public void handle(RequestSubmitted event) {
        Request request = requests.findById(event.requestId())
                .orElseThrow(() -> new NotFoundException("Request not found: " + event.requestId()));
        Long recipientId = request.getRequester().getId();
        EmailComposer.ComposedEmail email = emails.submitted(request);
        outbox.notifyFeed(event.requestId(), recipientId, OutboxEvents.NOTIFY_SUBMIT,
                email.subject(), email.text(), null);
        outbox.notifyEmail(event.requestId(), recipientId, OutboxEvents.SUBMITTED,
                email.subject(), email.text(), email.html(), null);
    }
}
