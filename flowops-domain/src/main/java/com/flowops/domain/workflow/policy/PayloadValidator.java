package com.flowops.domain.workflow.policy;

import com.flowops.domain.workflow.RequestType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Validates a request payload against a request type's JSONB-driven
 * {@code required_fields} and {@code validation_rules}.
 *
 * <p>
 * Pure domain service — no framework dependencies.
 *
 * Supported rule objects (per field):
 * <ul>
 * <li>{@code {"type": "number", "min": 1}} — value must be a number ≥ min</li>
 * <li>{@code {"pattern": "^[0-9]+d$"}} — string value must match the regex</li>
 * </ul>
 */
public class PayloadValidator {

    /**
     * @return field → violation message; empty map when the payload is valid.
     */
    public Map<String, String> validate(RequestType type, Map<String, Object> payload) {
        Map<String, String> violations = new LinkedHashMap<>();
        Map<String, Object> fields = payload != null ? payload : Map.of();

        for (Map.Entry<String, Object> entry : type.getRequiredFields().entrySet()) {
            String field = entry.getKey();
            Object value = fields.get(field);
            if (value == null) {
                violations.put(field, requiredMessage(type, field, "is required"));
            }
            else if (value instanceof String s && s.isBlank()) {
                violations.put(field, requiredMessage(type, field, "must not be blank"));
            }
        }

        for (Map.Entry<String, Object> entry : type.getValidationRules().entrySet()) {
            String field = entry.getKey();
            if (entry.getValue() instanceof Map<?, ?> rule && fields.get(field) != null) {
                applyRule(field, fields.get(field), rule, violations);
            }
        }
        return violations;
    }

    private void applyRule(String field, Object value, Map<?, ?> rule, Map<String, String> violations) {
        if ("number".equals(rule.get("type"))) {
            if (!(value instanceof Number n)) {
                violations.put(field, "must be a number");
                return;
            }
            if (rule.get("min") instanceof Number min && n.doubleValue() < min.doubleValue()) {
                violations.put(field, "must be >= " + min);
            }
        }
        if (rule.get("pattern") instanceof String regex) {
            String text = String.valueOf(value);
            if (!Pattern.matches(regex, text)) {
                violations.put(field, "must match pattern " + regex);
            }
        }
    }

    private String requiredMessage(RequestType type, String field, String suffix) {
        Object description = type.getRequiredFields().get(field);
        String base = description instanceof String s && !s.isBlank() ? s : field;
        return base + " — " + suffix;
    }

}
