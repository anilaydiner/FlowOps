package com.flowops.persistence;

import com.flowops.application.notification.model.EmailLogEntry;
import com.flowops.application.notification.port.EmailLogEntryRepository;
import com.flowops.persistence.entity.EmailLogEntryEntity;
import com.flowops.persistence.mapping.EmailLogEntryMappers;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** JPA adapter for the {@link EmailLogEntryRepository} port. Maps application delivery records ↔ entities. */
@Repository
public class SpringEmailLogEntryRepository implements EmailLogEntryRepository {

    private final EmailLogEntryJpaRepository jpaRepository;

    public SpringEmailLogEntryRepository(EmailLogEntryJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public EmailLogEntry save(EmailLogEntry entry) {
        EmailLogEntryEntity saved = jpaRepository.saveAndFlush(EmailLogEntryMappers.toEntity(entry));
        entry.setId(saved.getId());
        return entry;
    }

    @Override
    public List<EmailLogEntry> findByToAddressOrderByCreatedAtDesc(String toAddress) {
        return jpaRepository.findByToAddressOrderByCreatedAtDesc(toAddress)
                .stream().map(EmailLogEntryMappers::fromEntity).toList();
    }

    @Override
    public Optional<EmailLogEntry> findFirstByOutboxId(Long outboxId) {
        return jpaRepository.findFirstByOutboxId(outboxId).map(EmailLogEntryMappers::fromEntity);
    }

    @Override
    public long countByStatus(String status) {
        return jpaRepository.countByStatus(status);
    }

    @Override
    public List<EmailLogEntry> findAll() {
        return jpaRepository.findAll().stream().map(EmailLogEntryMappers::fromEntity).toList();
    }
}
