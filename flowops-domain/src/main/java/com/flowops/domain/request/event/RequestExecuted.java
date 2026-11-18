package com.flowops.domain.request.event;

import com.flowops.domain.event.DomainEvent;
import java.util.UUID;

public record RequestExecuted(Long requestId,UUID executionPlanId,int completedActions) implements DomainEvent {}
