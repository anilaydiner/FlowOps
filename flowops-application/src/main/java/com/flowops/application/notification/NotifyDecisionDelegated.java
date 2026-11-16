package com.flowops.application.notification;

import com.flowops.application.event.DomainEventHandler;
import com.flowops.domain.OutboxEvents;
import com.flowops.domain.Request;
import com.flowops.domain.event.DecisionDelegated;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.port.RequestRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(100)
public class NotifyDecisionDelegated implements DomainEventHandler<DecisionDelegated> {
    private final RequestRepository requests;
    private final EmailComposer emails;
    private final OutboxService outbox;

    public NotifyDecisionDelegated(RequestRepository requests, EmailComposer emails, OutboxService outbox) {
        this.requests = requests;
        this.emails = emails;
        this.outbox = outbox;
    }

    @Override
    public Class<DecisionDelegated> eventType() {
        return DecisionDelegated.class;
    }

    @Override
    public void handle(DecisionDelegated event) {
        Request request = requests.findById(event.requestId())
                .orElseThrow(() -> new NotFoundException("Request not found: " + event.requestId()));
        Long recipientId = event.delegatedTo();
        EmailComposer.ComposedEmail email = emails.delegated(request, event.delegatorUsername(), "the delegatee", event.stepCode());
        outbox.notifyFeed(event.requestId(), recipientId, OutboxEvents.NOTIFY_DELEGATE,
                email.subject(), email.text(), null);
        outbox.notifyEmail(event.requestId(), recipientId, OutboxEvents.DELEGATED,
                email.subject(), email.text(), email.html(), null);
    }
}
