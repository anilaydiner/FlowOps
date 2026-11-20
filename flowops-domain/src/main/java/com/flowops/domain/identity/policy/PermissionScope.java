package com.flowops.domain.identity.policy;

import com.flowops.domain.identity.ScopeType;

/** Immutable scope shared by authorization and compiled approval-queue predicates. */
public record PermissionScope(ScopeType type, String value) {
    private static final java.util.regex.Pattern DECIMAL = java.util.regex.Pattern
        .compile("[+-]?([0-9]+([.][0-9]*)?|[.][0-9]+)([eE][+-]?[0-9]+)?");

    public boolean matches(Long actorId, String departmentCode, TargetContext target) {
        if (type == null)
            return true;
        if (target == null)
            return false;
        return switch (type) {
            case SELF -> actorId != null && actorId.equals(target.requesterId());
            case DEPARTMENT -> same(departmentCode, target.departmentCode());
            case REQUEST_TYPE -> same(value, target.typeCode());
            case STEP -> same(value, target.stepCode());
            case THRESHOLD -> maximum() != null && target.amount() != null && Double.isFinite(target.amount())
                    && target.amount() <= maximum();
        };
    }

    public Double maximum() {
        return decimalNumber(value);
    }

    /**
     * Canonical decimal syntax also used by PostgreSQL's flowops_payload_amount function.
     */
    public static Double decimalNumber(String value) {
        if (value == null || !DECIMAL.matcher(value.trim()).matches())
            return null;
        try {
            var decimal = new java.math.BigDecimal(value.trim());
            double number = decimal.doubleValue();
            return Double.isFinite(number) && !(number == 0 && decimal.signum() != 0) ? number : null;
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean same(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }
}
