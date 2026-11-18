package com.flowops.application.request.query;

import com.flowops.application.mediator.Message;
import com.flowops.domain.execution.ExecutionPlan;
import com.flowops.domain.identity.CallerIdentity;

public record GetExecutionPlan(Long id,CallerIdentity caller) implements Message<GetExecutionPlan.View> {
    public record View(boolean known,ExecutionPlan plan) {}
}
