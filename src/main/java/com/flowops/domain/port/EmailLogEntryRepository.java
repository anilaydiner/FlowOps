package com.flowops.domain.port;

import com.flowops.domain.EmailLogEntry;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for {@link EmailLogEntry} audit records.
 */
public interface EmailLogEntryRepository {

    EmailLogEntry save(EmailLogEntry entry);

    List<EmailLogEntry> findByToAddressOrderByCreatedAtDesc(String toAddress);

    Optional<EmailLogEntry> findFirstByOutboxId(Long outboxId);

    long countByStatus(String status);
}
