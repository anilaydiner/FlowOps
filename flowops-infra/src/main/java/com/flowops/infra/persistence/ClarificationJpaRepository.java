package com.flowops.infra.persistence;

import com.flowops.infra.persistence.entity.ClarificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClarificationJpaRepository extends JpaRepository<ClarificationEntity, Long> {

    List<ClarificationEntity> findByRequestIdOrderByCreatedAtDesc(Long requestId);

    Optional<ClarificationEntity> findByIdAndRequestId(Long id, Long requestId);

    long countByRequestId(Long requestId);
}
