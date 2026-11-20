package com.flowops.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Defensively copies nested JSON objects and arrays, preserving null values. */
public final class JsonObjects {

    private JsonObjects() {
    }

    public static Map<String, Object> immutableCopy(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (source != null) {
            source.forEach((key, value) -> copy.put(key, copyJsonValue(value)));
        }
        return Collections.unmodifiableMap(copy);
    }

    // JSON objects and arrays can nest. Preserve nulls, key order and scalar types.
    private static Object copyJsonValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(key, copyJsonValue(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(nested -> copy.add(copyJsonValue(nested)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }

}
