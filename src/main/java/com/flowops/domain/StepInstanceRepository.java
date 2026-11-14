package com.flowops.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StepInstanceRepository extends JpaRepository<StepInstance, Long> {

    List<StepInstance> findByRequestIdOrderById(Long requestId);

    Optional<StepInstance> findTopByRequestIdAndStateOrderById(Long requestId, StepState state);

    long countByRequestIdAndState(Long requestId, StepState state);

    List<StepInstance> findByState(StepState state);

    @org.springframework.data.jpa.repository.Query(
            "SELECT DISTINCT si FROM StepInstance si JOIN FETCH si.step JOIN FETCH si.request WHERE si.state = :state")
    List<StepInstance> findByStateWithAssociations(@org.springframework.data.repository.query.Param("state") StepState state);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(value = "UPDATE step_instances SET created_at = :ts WHERE id = :id", nativeQuery = true)
    void backdateCreatedAt(@org.springframework.data.repository.query.Param("id") Long id,
                           @org.springframework.data.repository.query.Param("ts") java.time.Instant ts);

    /**
     * Finds the ids of requests that have a PENDING step whose required role
     * matches any of the caller's roles, excluding requests the caller created
     * (SoD). Used by the /queue endpoint.
     */
    @org.springframework.data.jpa.repository.Query(
            "SELECT DISTINCT si.request.id FROM StepInstance si " +
            "WHERE si.state = :state " +
            "AND si.request.status = :status " +
            "AND si.step.requiredRole IN :roles " +
            "AND si.request.requester.id <> :userId")
    java.util.List<Long> findEligibleRequestIds(
            @org.springframework.data.repository.query.Param("state") StepState state,
            @org.springframework.data.repository.query.Param("status") RequestStatus status,
            @org.springframework.data.repository.query.Param("roles") java.util.Collection<String> roles,
            @org.springframework.data.repository.query.Param("userId") Long userId);
}
