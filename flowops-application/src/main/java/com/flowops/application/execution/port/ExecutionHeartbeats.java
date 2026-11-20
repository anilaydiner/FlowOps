package com.flowops.application.execution.port;

import com.flowops.domain.execution.ExecutionLease;

/**
 * Infrastructure owns scheduling; each renewal delegates to the transactional use case.
 */
public interface ExecutionHeartbeats {

    interface Registration extends AutoCloseable {

        @Override
        void close();

    }

    Registration start(ExecutionLease lease, java.time.Instant deadline);

}
