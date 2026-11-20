package com.flowops.persistence;

import com.flowops.application.notification.outbox.OutboxPayload;
import com.flowops.application.notification.outbox.port.OutboxPayloadCodec;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Jackson-backed {@link OutboxPayloadCodec}.
 *
 * <p>
 * Owns the on-disk JSON shape (field names and order). The encoder writes only present
 * (non-null) fields — matching the historical payload format, so existing rows and any
 * external consumers see an unchanged shape.
 */
@Component
public class JacksonOutboxPayloadCodec implements OutboxPayloadCodec {

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String encode(OutboxPayload p) {
        Map<String, Object> fields = new LinkedHashMap<>();
        putIfPresent(fields, "requestId", p.requestId());
        putIfPresent(fields, "type", p.type());
        putIfPresent(fields, "title", p.title());
        putIfPresent(fields, "subject", p.subject());
        putIfPresent(fields, "body", p.body());
        putIfPresent(fields, "html", p.html());
        putIfPresent(fields, "step", p.step());
        try {
            return mapper.writeValueAsString(fields);
        }
        catch (JacksonException e) {
            throw new IllegalStateException("Failed to encode outbox payload", e);
        }
    }

    @Override
    public OutboxPayload decode(String json) {
        try {
            return mapper.readValue(json, OutboxPayload.class);
        }
        catch (JacksonException e) {
            throw new IllegalStateException("Failed to decode outbox payload: " + json, e);
        }
    }

    private static void putIfPresent(Map<String, Object> fields, String key, Object value) {
        if (value != null) {
            fields.put(key, value);
        }
    }

}
