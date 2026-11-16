package com.flowops.infra.persistence;

import com.flowops.domain.StepState;
import com.flowops.infra.persistence.entity.StepInstanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
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

}
