package com.flowops.domain.port;

import com.flowops.domain.StepInstance;
import com.flowops.domain.StepState;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for {@link StepInstance}.
 */
public interface StepInstanceRepository {

    StepInstance save(StepInstance step);

    List<StepInstance> findByRequestIdOrderById(Long requestId);

    Optional<StepInstance> findTopByRequestIdAndStateOrderById(Long requestId, StepState state);

    long countByRequestIdAndState(Long requestId, StepState state);

    List<StepInstance> findByState(StepState state);

    /** PENDING steps with step + request eagerly loaded (avoids N+1 in the SLA scanner). */
    List<StepInstance> findByStateWithAssociations(StepState state);

    void backdateCreatedAt(Long id, Instant ts);
}
