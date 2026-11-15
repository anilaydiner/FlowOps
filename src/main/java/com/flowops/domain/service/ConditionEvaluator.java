package com.flowops.domain.service;

import java.util.Map;

/**
 * Evaluates workflow step conditions (JSONB) against a request payload.
 *
 * <p>Pure domain service — no framework dependencies.
 *
 * Condition shape: {"field": "amount", "op": ">", "value": 5000}
 * Supported ops: >, >=, <, <=, ==, !=. A null/empty condition always applies.
 * A missing field in the payload fails the condition (step is skipped).
 */
public class ConditionEvaluator {

    public boolean evaluatesTo(Map<String, Object> condition, Map<String, Object> payload) {
        if (condition == null || condition.isEmpty()) {
            return true;
        }
        Object field = condition.get("field");
        Object op = condition.get("op");
        if (field == null || op == null) {
            return true;
        }
        Object actual = payload != null ? payload.get(field.toString()) : null;
        if (actual == null) {
            return false;
        }
        Object expected = condition.get("value");
        String operator = op.toString().toLowerCase();
        return switch (operator) {
            case ">" -> compare(actual, expected) > 0;
            case ">=" -> compare(actual, expected) >= 0;
            case "<" -> compare(actual, expected) < 0;
            case "<=" -> compare(actual, expected) <= 0;
            case "==", "eq" -> looseEquals(actual, expected);
            case "!=", "ne" -> !looseEquals(actual, expected);
            default -> throw new IllegalArgumentException("Unsupported condition operator: " + op);
        };
    }

    private int compare(Object actual, Object expected) {
        if (actual instanceof Number a && expected instanceof Number e) {
            return Double.compare(a.doubleValue(), e.doubleValue());
        }
        if (actual instanceof String as && expected instanceof String es) {
            return as.compareTo(es);
        }
        throw new IllegalArgumentException(
                "Cannot compare payload value of type " + actual.getClass().getSimpleName()
                        + " with condition value of type "
                        + (expected == null ? "null" : expected.getClass().getSimpleName()));
    }

    private boolean looseEquals(Object actual, Object expected) {
        if (actual instanceof Number a && expected instanceof Number e) {
            return Double.compare(a.doubleValue(), e.doubleValue()) == 0;
        }
        return actual.equals(expected);
    }
}
