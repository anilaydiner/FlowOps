package com.flowops.domain.port;

import com.flowops.domain.OutboxMessage;
import com.flowops.domain.OutboxStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for {@link OutboxMessage} records.
 */
public interface OutboxMessageRepository {

    OutboxMessage save(OutboxMessage message);

    List<OutboxMessage> saveAll(List<OutboxMessage> messages);

    Optional<OutboxMessage> findByIdempotencyKey(String idempotencyKey);

    long countByStatus(OutboxStatus status);

    /** Claim a batch of dispatchable messages (FOR UPDATE SKIP LOCKED). */
    List<OutboxMessage> claimBatch(Instant now, int limit);

    List<OutboxMessage> findByKeyPrefix(String prefix);
}
