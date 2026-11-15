package com.flowops.domain.port;

import com.flowops.domain.OutboxPayload;

/**
 * Storage codec for {@link OutboxPayload}: translates between the domain
 * value object and the JSON string persisted in the outbox table.
 *
 * <p>Implementations live in the persistence adapters (infra); the domain
 * and application layers only ever see the typed payload.
 */
public interface OutboxPayloadCodec {

    String encode(OutboxPayload payload);

    OutboxPayload decode(String json);
}
