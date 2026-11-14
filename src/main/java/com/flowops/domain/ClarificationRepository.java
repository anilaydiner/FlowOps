package com.flowops.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClarificationRepository extends JpaRepository<Clarification, Long> {

    List<Clarification> findByRequestIdOrderByCreatedAtDesc(Long requestId);

    Optional<Clarification> findByIdAndRequestId(Long id, Long requestId);

    long countByRequestId(Long requestId);
}
