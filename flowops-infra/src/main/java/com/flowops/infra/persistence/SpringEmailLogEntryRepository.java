package com.flowops.infra.persistence;

import com.flowops.domain.EmailLogEntry;
import com.flowops.domain.port.EmailLogEntryRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class SpringEmailLogEntryRepository implements EmailLogEntryRepository {

    private final EmailLogEntryJpaRepository jpaRepository;

    public SpringEmailLogEntryRepository(EmailLogEntryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public EmailLogEntry save(EmailLogEntry entry) {
        return jpaRepository.save(entry);
    }

    @Override
    public List<EmailLogEntry> findByToAddressOrderByCreatedAtDesc(String toAddress) {
        return jpaRepository.findByToAddressOrderByCreatedAtDesc(toAddress);
    }

    @Override
    public Optional<EmailLogEntry> findFirstByOutboxId(Long outboxId) {
        return jpaRepository.findFirstByOutboxId(outboxId);
    }

    @Override
    public long countByStatus(String status) {
        return jpaRepository.countByStatus(status);
    }
}
