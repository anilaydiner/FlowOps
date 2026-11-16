package com.flowops.application.notification;

import com.flowops.application.event.DomainEventHandler;
import com.flowops.application.usecase.ApproverNotifier;
import com.flowops.domain.Request;
import com.flowops.domain.RequestStatus;
import com.flowops.domain.event.RequestSubmitted;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.port.RequestRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(200)
public class NotifySubmittedRequestApprovers implements DomainEventHandler<RequestSubmitted> {
    private final RequestRepository requests;
    private final ApproverNotifier approvers;

    public NotifySubmittedRequestApprovers(RequestRepository requests, ApproverNotifier approvers) {
        this.requests = requests;
        this.approvers = approvers;
    }

    @Override
    public Class<RequestSubmitted> eventType() {
        return RequestSubmitted.class;
    }

    @Override
    public void handle(RequestSubmitted event) {
        Request request = requests.findById(event.requestId())
                .orElseThrow(() -> new NotFoundException("Request not found: " + event.requestId()));
        if (request.getStatus() == RequestStatus.IN_REVIEW) {
            approvers.notifyEligibleApprovers(request, null);
        }
    }
}
