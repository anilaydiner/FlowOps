package com.flowops.persistence;

import com.flowops.domain.request.DecisionAction;
import com.flowops.persistence.entity.DecisionEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DecisionJpaRepository extends JpaRepository<DecisionEntity, Long> {

    List<DecisionEntity> findByStepInstanceIdOrderById(Long stepInstanceId);

    List<DecisionEntity> findByRequestIdOrderById(Long requestId);

    long countByStepInstanceIdAndAction(Long stepInstanceId, DecisionAction action);
    @Query(value = "SELECT count(*) FROM step_approval_votes WHERE step_instance_id = :stepId", nativeQuery = true)
    long countDistinctApprovers(@Param("stepId") Long stepInstanceId);
}
