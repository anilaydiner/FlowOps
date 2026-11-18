package com.flowops.domain.request.port;

import com.flowops.domain.request.StepInstance;
import com.flowops.domain.request.StepState;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Persistence port for {@link StepInstance}.
 */
public interface StepInstanceRepository {

    StepInstance save(StepInstance step);

    boolean existsByWorkflowId(Long workflowId);

    /** Discover parent ids in ascending order without caching mutable aggregate state. */
    List<Long> findPendingRequestIds();

    /** Keyset discovery; a null dueAt selects all active review work. */
    List<Long> findReviewWork(long afterRequestId, int limit, Instant dueAt);

    long reviewWorkHighWatermark();

    /** Reload the pending step after acquiring its parent request write lock. */
    Optional<StepInstance> findPendingForLockedRequest(Long requestId);

    /** Reload PENDING or BLOCKED state after acquiring the parent write lock. */
    Optional<StepInstance> findActiveForLockedRequest(Long requestId);

    List<StepInstance> findByRequestIdOrderById(Long requestId);

    Set<Long> findMaterializedDefinitionIds(Long requestId);

    Optional<StepInstance> findTopByRequestIdAndStateOrderById(Long requestId, StepState state);

    long countByRequestIdAndState(Long requestId, StepState state);

    List<StepInstance> findByState(StepState state);

    /** PENDING steps with step + request eagerly loaded, for read-only consumers. */
    List<StepInstance> findByStateWithAssociations(StepState state);

}
