package com.flowops.domain.port;

import com.flowops.domain.Decision;
import com.flowops.domain.DecisionAction;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for {@link Decision} records.
 */
public interface DecisionRepository {

    Decision save(Decision decision);

    List<Decision> findByStepInstanceIdOrderById(Long stepInstanceId);

    List<Decision> findByRequestIdOrderById(Long requestId);

    long countByStepInstanceIdAndAction(Long stepInstanceId, DecisionAction action);

    List<Decision> findAll();

    Optional<Decision> findById(Long id);
}
