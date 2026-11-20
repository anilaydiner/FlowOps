package com.flowops.application.execution.port;

import com.flowops.application.execution.model.ExecutionRecovery.*;
import java.util.*;

public interface ExecutionRecoveryRepository {

    void append(Command command);

    void append(Observation observation);

    Optional<Entry> find(UUID commandId);

    Optional<Entry> latest(Long jobId);

    List<Entry> history(Long requestId, int limit);

    /**
     * A provider-backed permission for exactly the next attempt; never a reusable retry
     * override.
     */
    boolean hasProviderRetryAuthorization(Long jobId, int attemptCount);

}
