package com.flowops.application.notification.outbox.port;

import com.flowops.application.notification.outbox.OutboxMessage;
import com.flowops.application.notification.outbox.OutboxStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for {@link OutboxMessage} records.
 */
public interface OutboxMessageRepository {

    /** Default number of rows claimed per worker tick. */
    int DEFAULT_BATCH_SIZE = 50;

    OutboxMessage save(OutboxMessage message);

    /**
     * Save unless a message with the same idempotency key already exists.
     * Returns the saved message, or empty when the write was skipped
     * (idempotent duplicate, including concurrent inserts).
     */
    Optional<OutboxMessage> saveIfAbsent(OutboxMessage message);

    List<OutboxMessage> saveAll(List<OutboxMessage> messages);

    Optional<OutboxMessage> findByIdempotencyKey(String idempotencyKey);

    long countByStatus(OutboxStatus status);

    /** Total number of outbox messages (test/operational support). */
    long count();

    /** Claim a batch of dispatchable messages (FOR UPDATE SKIP LOCKED). */
    List<OutboxMessage> claimBatch(Instant now, int limit);

    List<OutboxMessage> findByKeyPrefix(String prefix);

    /** All outbox messages (test/operational support). */
    List<OutboxMessage> findAll();

    Optional<OutboxMessage> findById(Long id);
}
