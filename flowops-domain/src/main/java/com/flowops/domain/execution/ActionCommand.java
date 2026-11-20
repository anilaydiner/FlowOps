package com.flowops.domain.execution;

import java.util.*;

/**
 * Frozen provider input. The stable key identifies the logical action across attempts.
 */
public record ActionCommand(String idempotencyKey, Long requestId, String action, Map<String, Object> payload,
        String correlationId) {
    public ActionCommand {
        payload = freeze(payload);
    }

    public static Map<String, Object> freeze(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source != null)
            source.forEach((key, value) -> copy.put(key, freezeValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object freezeValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, item) -> copy.put(key, freezeValue(item)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list)
            return Collections.unmodifiableList(list.stream().map(ActionCommand::freezeValue).toList());
        return value;
    }
}
