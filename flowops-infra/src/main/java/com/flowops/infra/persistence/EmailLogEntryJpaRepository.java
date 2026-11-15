package com.flowops.infra.persistence;

import com.flowops.infra.persistence.entity.EmailLogEntryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmailLogEntryJpaRepository extends JpaRepository<EmailLogEntryEntity, Long> {

    List<EmailLogEntryEntity> findByToAddressOrderByCreatedAtDesc(String toAddress);

    Optional<EmailLogEntryEntity> findFirstByOutboxId(Long outboxId);

    long countByStatus(String status);
}
