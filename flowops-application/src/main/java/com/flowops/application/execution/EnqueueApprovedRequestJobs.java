package com.flowops.application.execution;

import com.flowops.application.event.DomainEventHandler;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.request.Request;
import com.flowops.domain.request.event.RequestApproved;
import com.flowops.domain.request.port.RequestRepository;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(200)
public class EnqueueApprovedRequestJobs implements DomainEventHandler<RequestApproved> {
    private final RequestRepository requests;
    private final ExecutionService execution;

    public EnqueueApprovedRequestJobs(RequestRepository requests, ExecutionService execution) {
        this.requests = requests;
        this.execution = execution;
    }

    @Override
    public Class<RequestApproved> eventType() {
        return RequestApproved.class;
    }

    @Override
    public void handle(RequestApproved event) {
        Request request = requests.findById(event.requestId())
                .orElseThrow(() -> new NotFoundException("Request not found: " + event.requestId()));
        execution.enqueueJobs(request);
    }
}
