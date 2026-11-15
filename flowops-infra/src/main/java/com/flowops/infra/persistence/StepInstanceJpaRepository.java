package com.flowops.infra.persistence;

import com.flowops.domain.DecisionAction;
import com.flowops.domain.RequestStatus;
import com.flowops.domain.StepState;
import com.flowops.infra.persistence.entity.StepInstanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StepInstanceJpaRepository extends JpaRepository<StepInstanceEntity, Long> {

    List<StepInstanceEntity> findByRequestIdOrderById(Long requestId);

    Optional<StepInstanceEntity> findTopByRequestIdAndStateOrderById(Long requestId, StepState state);

    long countByRequestIdAndState(Long requestId, StepState state);

    List<StepInstanceEntity> findByState(StepState state);

    @Query("SELECT DISTINCT si FROM StepInstance si JOIN FETCH si.step JOIN FETCH si.request WHERE si.state = :state")
    List<StepInstanceEntity> findByStateWithAssociations(@Param("state") StepState state);

    @Modifying
    @Query(value = "UPDATE step_instances SET created_at = :ts WHERE id = :id", nativeQuery = true)
    void backdateCreatedAt(@Param("id") Long id, @Param("ts") Instant ts);

    /**
     * Request ids the caller may act on: the current step's required role is in
     * the caller's roles, <b>or</b> the decision was delegated to the caller on
     * that step. Excludes the caller's own requests (SoD).
     */
    @Query(
            "SELECT DISTINCT si.request.id FROM StepInstance si " +
            "WHERE si.state = :state " +
            "AND si.request.status = :status " +
            "AND si.request.requester.id <> :userId " +
            "AND ( si.step.requiredRole IN :roles " +
            "   OR EXISTS (SELECT 1 FROM Decision d " +
            "              WHERE d.stepInstance.id = si.id " +
            "                AND d.action = :delegateAction " +
            "                AND d.delegatedTo = :userId) )")
    List<Long> findEligibleRequestIds(
            @Param("state") StepState state,
            @Param("status") RequestStatus status,
            @Param("roles") Collection<String> roles,
            @Param("delegateAction") DecisionAction delegateAction,
            @Param("userId") Long userId);
}
