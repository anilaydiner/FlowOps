package com.flowops.infra.persistence;

import com.flowops.domain.OutboxMessage;
import com.flowops.domain.OutboxStatus;
import com.flowops.domain.port.OutboxMessageRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public class SpringOutboxMessageRepository implements OutboxMessageRepository {

    private final OutboxMessageJpaRepository jpaRepository;

    public SpringOutboxMessageRepository(OutboxMessageJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public OutboxMessage save(OutboxMessage message) {
        return jpaRepository.save(message);
    }

    @Override
    public Optional<OutboxMessage> saveIfAbsent(OutboxMessage message) {
        if (jpaRepository.findByIdempotencyKey(message.getIdempotencyKey()).isPresent()) {
            return Optional.empty();
        }
        try {
            return Optional.of(jpaRepository.save(message));
        } catch (DataIntegrityViolationException e) {
            // Concurrent insert of the same idempotency key — safe to skip.
            return Optional.empty();
        }
    }

    @Override
    public List<OutboxMessage> saveAll(List<OutboxMessage> messages) {
        return jpaRepository.saveAll(messages);
    }

    @Override
    public Optional<OutboxMessage> findByIdempotencyKey(String idempotencyKey) {
        return jpaRepository.findByIdempotencyKey(idempotencyKey);
    }

    @Override
    public long countByStatus(OutboxStatus status) {
        return jpaRepository.countByStatus(status);
    }

    @Override
    public List<OutboxMessage> claimBatch(Instant now, int limit) {
        return jpaRepository.claimBatch(now, limit);
    }

    @Override
    public List<OutboxMessage> findByKeyPrefix(String prefix) {
        return jpaRepository.findByKeyPrefix(prefix);
    }
}
