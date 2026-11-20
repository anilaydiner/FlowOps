package com.flowops.domain.execution.port;

import com.flowops.domain.execution.ExecutionPlan;
import com.flowops.domain.request.Request.ExecutionCompletion;
import java.util.*;

public interface ExecutionPlanRepository {

    Optional<ExecutionPlan.Configuration> configuration(Long typeId);

    void save(ExecutionPlan plan);

    Optional<ExecutionPlan> findByRequestId(Long requestId);

    void bindJob(UUID planId, String action, Long jobId);

    boolean jobMatches(UUID planId, Long jobId);

    boolean jobsMatch(UUID planId);

    boolean allCompleted(UUID planId);

    default Optional<ExecutionCompletion> completionOf(ExecutionPlan plan) {
        return allCompleted(plan.id()) ? Optional.of(new ExecutionCompletion(plan.id(), plan.requestId()))
                : Optional.empty();
    }

}
