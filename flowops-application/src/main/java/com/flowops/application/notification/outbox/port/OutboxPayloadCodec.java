package com.flowops.application.notification.outbox.port;

import com.flowops.application.notification.outbox.OutboxPayload;

/**
 * Storage codec for {@link OutboxPayload}: translates between the application payload and
 * the JSON string persisted in the outbox table.
 *
 * <p>
 * Implementations live in persistence adapters; application delivery code uses this
 * contract without depending on a JSON library. The business domain has no dependency on
 * the outbox payload or its storage codec.
 */
public interface OutboxPayloadCodec {

    String encode(OutboxPayload payload);

    OutboxPayload decode(String json);

}
