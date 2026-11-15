package com.flowops.infra.persistence;

import com.flowops.domain.RequestStatus;
import com.flowops.domain.StepInstance;
import com.flowops.domain.StepState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StepInstanceJpaRepository extends JpaRepository<StepInstance, Long> {

    List<StepInstance> findByRequestIdOrderById(Long requestId);

    Optional<StepInstance> findTopByRequestIdAndStateOrderById(Long requestId, StepState state);

    long countByRequestIdAndState(Long requestId, StepState state);

    List<StepInstance> findByState(StepState state);

    @Query("SELECT DISTINCT si FROM StepInstance si JOIN FETCH si.step JOIN FETCH si.request WHERE si.state = :state")
    List<StepInstance> findByStateWithAssociations(@Param("state") StepState state);

    @Modifying
    @Query(value = "UPDATE step_instances SET created_at = :ts WHERE id = :id", nativeQuery = true)
    void backdateCreatedAt(@Param("id") Long id, @Param("ts") Instant ts);

    @Query(
            "SELECT DISTINCT si.request.id FROM StepInstance si " +
            "WHERE si.state = :state " +
            "AND si.request.status = :status " +
            "AND si.step.requiredRole IN :roles " +
            "AND si.request.requester.id <> :userId")
    List<Long> findEligibleRequestIds(
            @Param("state") StepState state,
            @Param("status") RequestStatus status,
            @Param("roles") Collection<String> roles,
            @Param("userId") Long userId);
}
