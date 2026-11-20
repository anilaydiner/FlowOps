package com.flowops.persistence;

import com.flowops.application.notification.outbox.OutboxMessage;
import com.flowops.application.notification.outbox.OutboxStatus;
import com.flowops.application.notification.outbox.port.OutboxMessageRepository;
import com.flowops.persistence.entity.OutboxMessageEntity;
import com.flowops.persistence.mapping.OutboxMessageMappers;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

/**
 * JPA adapter for the {@link OutboxMessageRepository} port. Maps application delivery
 * records ↔ entities.
 */
@Repository
public class SpringOutboxMessageRepository implements OutboxMessageRepository {

    private final OutboxMessageJpaRepository jpaRepository;

    public SpringOutboxMessageRepository(OutboxMessageJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public OutboxMessage save(OutboxMessage message) {
        OutboxMessageEntity saved = jpaRepository.saveAndFlush(OutboxMessageMappers.toEntity(message));
        backfill(message, saved);
        return message;
    }

    @Override
    public Optional<OutboxMessage> saveIfAbsent(OutboxMessage message) {
        if (jpaRepository.findByIdempotencyKey(message.getIdempotencyKey()).isPresent()) {
            return Optional.empty();
        }
        try {
            OutboxMessageEntity saved = jpaRepository.saveAndFlush(OutboxMessageMappers.toEntity(message));
            backfill(message, saved);
            return Optional.of(message);
        }
        catch (DataIntegrityViolationException e) {
            // Concurrent insert of the same idempotency key — safe to skip.
            return Optional.empty();
        }
    }

    @Override
    public List<OutboxMessage> saveAll(List<OutboxMessage> messages) {
        List<OutboxMessageEntity> saved = jpaRepository
            .saveAllAndFlush(messages.stream().map(OutboxMessageMappers::toEntity).toList());
        for (int i = 0; i < messages.size(); i++) {
            messages.get(i).setId(saved.get(i).getId());
        }
        return messages;
    }

    @Override
    public Optional<OutboxMessage> findByIdempotencyKey(String idempotencyKey) {
        return jpaRepository.findByIdempotencyKey(idempotencyKey).map(OutboxMessageMappers::fromEntity);
    }

    @Override
    public long countByStatus(OutboxStatus status) {
        return jpaRepository.countByStatus(status);
    }

    @Override
    public long count() {
        return jpaRepository.count();
    }

    @Override
    public List<OutboxMessage> claimBatch(Instant now, int limit) {
        return jpaRepository.claimBatch(now, limit).stream().map(OutboxMessageMappers::fromEntity).toList();
    }

    @Override
    public List<OutboxMessage> findByKeyPrefix(String prefix) {
        return jpaRepository.findByKeyPrefix(prefix).stream().map(OutboxMessageMappers::fromEntity).toList();
    }

    @Override
    public List<OutboxMessage> findAll() {
        return jpaRepository.findAll().stream().map(OutboxMessageMappers::fromEntity).toList();
    }

    @Override
    public Optional<OutboxMessage> findById(Long id) {
        return jpaRepository.findById(id).map(OutboxMessageMappers::fromEntity);
    }

    private static void backfill(OutboxMessage d, OutboxMessageEntity e) {
        d.setId(e.getId());
    }

}
