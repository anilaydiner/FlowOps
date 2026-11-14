package com.flowops.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface EmailLogEntryRepository extends JpaRepository<EmailLogEntry, Long> {

    List<EmailLogEntry> findByToAddressOrderByCreatedAtDesc(String toAddress);

    Optional<EmailLogEntry> findFirstByOutboxId(Long outboxId);

    long countByStatus(String status);
}
