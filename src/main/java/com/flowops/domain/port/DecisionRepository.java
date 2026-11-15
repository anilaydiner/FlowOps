package com.flowops.domain.port;

import com.flowops.domain.Decision;
import com.flowops.domain.DecisionAction;

import java.util.List;

/**
 * Persistence port for {@link Decision} records.
 */
public interface DecisionRepository {

    Decision save(Decision decision);

    List<Decision> findByStepInstanceIdOrderById(Long stepInstanceId);

    long countByStepInstanceIdAndAction(Long stepInstanceId, DecisionAction action);
}
