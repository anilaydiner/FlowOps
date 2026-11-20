package com.flowops.application.execution.port;

import com.flowops.application.execution.model.ExecutionPlanRecovery.Materialization;
import java.util.*;

public interface ExecutionPlanReconciliationRepository {

    long highWatermark();

    List<Long> candidates(long after, long through, int limit);

    boolean manifestValid(UUID planId, Long requestId);

    Map<String, Long> bindings(UUID planId);

    Map<String, Materialization> materializations(UUID planId);

    boolean actionMatches(UUID planId, String action, Long jobId);

    boolean executionHistory(Long requestId, String action, String key, Long jobId);

    boolean anyMaterializationHistory(Long requestId, String action, String key);

    boolean completionRecorded(Long jobId);

    boolean auditRecorded(Long requestId, String suffix);

}
