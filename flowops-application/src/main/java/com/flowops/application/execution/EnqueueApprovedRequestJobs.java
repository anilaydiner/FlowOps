package com.flowops.application.execution;

import com.flowops.application.event.DomainEventHandler;
import com.flowops.application.request.port.RequestWriteLock;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.request.Request;
import com.flowops.domain.request.event.RequestApproved;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(200)
public class EnqueueApprovedRequestJobs implements DomainEventHandler<RequestApproved> {
    private final RequestWriteLock requestWriteLock;
    private final ExecutionService execution;

    public EnqueueApprovedRequestJobs(RequestWriteLock requestWriteLock, ExecutionService execution) {
        this.requestWriteLock = requestWriteLock;
        this.execution = execution;
    }

    @Override
    public Class<RequestApproved> eventType() {
        return RequestApproved.class;
    }

    @Override
    public void handle(RequestApproved event) {
        Request request = requestWriteLock.findByIdForUpdate(event.requestId())
                .orElseThrow(() -> new NotFoundException("Request not found: " + event.requestId()));
        if(!event.executionPlanId().equals(request.getExecutionPlanId()))throw new com.flowops.domain.exception.ConflictException("approval-event-mismatch","Approval plan identity differs");
        execution.enqueueJobs(request);
    }
}
