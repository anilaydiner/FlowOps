package com.flowops.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DecisionRepository extends JpaRepository<Decision, Long> {

    List<Decision> findByStepInstanceIdOrderById(Long stepInstanceId);

    /** Count of APPROVE decisions on a step instance (drives completion rules). */
    long countByStepInstanceIdAndAction(Long stepInstanceId, DecisionAction action);
}
