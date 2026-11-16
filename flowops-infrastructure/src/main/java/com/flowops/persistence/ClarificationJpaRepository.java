package com.flowops.persistence;

import com.flowops.persistence.entity.ClarificationEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClarificationJpaRepository extends JpaRepository<ClarificationEntity, Long> {

    List<ClarificationEntity> findByRequestIdOrderByCreatedAtDesc(Long requestId);

    Optional<ClarificationEntity> findByIdAndRequestId(Long id, Long requestId);

    long countByRequestId(Long requestId);
}
