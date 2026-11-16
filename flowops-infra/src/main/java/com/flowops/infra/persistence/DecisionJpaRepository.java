package com.flowops.infra.persistence;

import com.flowops.domain.DecisionAction;
import com.flowops.infra.persistence.entity.DecisionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DecisionJpaRepository extends JpaRepository<DecisionEntity, Long> {

    List<DecisionEntity> findByStepInstanceIdOrderById(Long stepInstanceId);

    List<DecisionEntity> findByRequestIdOrderById(Long requestId);

    long countByStepInstanceIdAndAction(Long stepInstanceId, DecisionAction action);
}
