package com.flowops.domain.policy;

/**
 * The target context for an authorization check (PLAN §7).
 * Carries the attributes of the request/step being acted upon,
 * so the evaluator can match scope constraints.
 *
 * @param requesterId    the ID of the user who created the request
 * @param typeCode       the request type code (e.g. "EXPENSE")
 * @param departmentCode the owning department code (e.g. "FINANCE")
 * @param amount         the amount from the payload (for THRESHOLD scope); null if absent
 * @param stepCode       the workflow step code (e.g. "FINANCE_REVIEW"); null if not step-specific
 */
public record TargetContext(
        Long requesterId,
        String typeCode,
        String departmentCode,
        Double amount,
        String stepCode
) {
    /** Build from a request + step (the common case in the engine). */
    public static TargetContext from(com.flowops.domain.Request request,
                                     com.flowops.domain.WorkflowStep step) {
        String typeCode = request.getType().getCode();
        String deptCode = request.getType().getOwningDepartment() != null
                ? request.getType().getOwningDepartment().getCode()
                : null;
        Double amount = extractAmount(request.getPayload());
        String stepCode = step != null ? step.getCode() : null;
        return new TargetContext(
                request.getRequester().getId(),
                typeCode,
                deptCode,
                amount,
                stepCode
        );
    }

    /** Build request-read scope from scalar data without requiring an aggregate. */
    public static TargetContext forRequest(Long requesterId, String typeCode, String departmentCode,
                                           java.util.Map<String, Object> payload) {
        return new TargetContext(requesterId, typeCode, departmentCode, extractAmount(payload), null);
    }

    private static Double extractAmount(java.util.Map<String, Object> payload) {
        if (payload == null) return null;
        Object v = payload.get("amount");
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String s) {
            try { return Double.parseDouble(s); } catch (NumberFormatException ignored) {}
        }
        return null;
    }
}
