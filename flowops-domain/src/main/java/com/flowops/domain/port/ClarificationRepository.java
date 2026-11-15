package com.flowops.domain.port;

import com.flowops.domain.Clarification;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for {@link Clarification} Q&A.
 */
public interface ClarificationRepository {

    Clarification save(Clarification clarification);

    List<Clarification> findByRequestIdOrderByCreatedAtDesc(Long requestId);

    Optional<Clarification> findByIdAndRequestId(Long id, Long requestId);

    long countByRequestId(Long requestId);

    Optional<Clarification> findById(Long id);
}
