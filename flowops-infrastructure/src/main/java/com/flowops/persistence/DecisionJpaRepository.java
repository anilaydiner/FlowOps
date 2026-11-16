package com.flowops.persistence;

import com.flowops.domain.request.DecisionAction;
import com.flowops.persistence.entity.DecisionEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DecisionJpaRepository extends JpaRepository<DecisionEntity, Long> {

    List<DecisionEntity> findByStepInstanceIdOrderById(Long stepInstanceId);

    List<DecisionEntity> findByRequestIdOrderById(Long requestId);

    long countByStepInstanceIdAndAction(Long stepInstanceId, DecisionAction action);
}
