package com.flowops.infra.persistence;

import com.flowops.domain.Decision;
import com.flowops.domain.DecisionAction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DecisionJpaRepository extends JpaRepository<Decision, Long> {

    List<Decision> findByStepInstanceIdOrderById(Long stepInstanceId);

    long countByStepInstanceIdAndAction(Long stepInstanceId, DecisionAction action);
}
