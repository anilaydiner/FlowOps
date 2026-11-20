package com.flowops.persistence;

import com.flowops.domain.request.StepState;
import com.flowops.persistence.entity.StepInstanceEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StepInstanceJpaRepository extends JpaRepository<StepInstanceEntity, Long> {

    @Query("SELECT count(si) > 0 FROM StepInstance si WHERE si.step.workflow.id = :workflowId")
    boolean existsByWorkflowId(@Param("workflowId") Long workflowId);

    List<StepInstanceEntity> findByRequestIdOrderById(Long requestId);

    Optional<StepInstanceEntity> findTopByRequestIdAndStateOrderById(Long requestId, StepState state);

    Optional<StepInstanceEntity> findTopByRequestIdAndStateInOrderById(Long requestId, List<StepState> states);

    long countByRequestIdAndState(Long requestId, StepState state);

    List<StepInstanceEntity> findByState(StepState state);

    @Query("SELECT DISTINCT si FROM StepInstance si JOIN FETCH si.step JOIN FETCH si.request WHERE si.state = :state")
    List<StepInstanceEntity> findByStateWithAssociations(@Param("state") StepState state);

    @Query("SELECT si.step.id FROM StepInstance si WHERE si.request.id = :requestId")
    java.util.Set<Long> findMaterializedDefinitionIds(@Param("requestId") Long requestId);

}
