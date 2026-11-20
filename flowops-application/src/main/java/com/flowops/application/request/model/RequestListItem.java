package com.flowops.application.request.model;

import com.flowops.domain.request.RequestStatus;
import java.time.Instant;
import java.util.Map;

/**
 * Scalar request header for list, queue and detail reads; no aggregate or persistence
 * context is required.
 */
public record RequestListItem(Long id, String typeCode, String typeName, String departmentCode, RequestStatus status,
        Map<String, Object> payload, Long version, Long requesterId, String requesterUsername,
        String requesterDisplayName, String correlationId, Instant createdAt, Instant updatedAt, Instant submittedAt,
        Instant executionPlannedAt, Instant executionStartedAt, boolean executionStartUnknown,
        boolean executionStartInferred, java.util.UUID executionPlanId,
        com.flowops.domain.request.RequestOutcome outcome, Instant approvedAt, Instant rejectedAt, Instant executedAt,
        Instant cancelledAt, Instant expiredAt, Instant closedAt, ExecutionProgress executionProgress) {
    public RequestListItem(Long id, String typeCode, String typeName, String departmentCode, RequestStatus status,
            Map<String, Object> payload, Long version, Long requesterId, String requesterUsername,
            String requesterDisplayName, String correlationId, Instant createdAt, Instant updatedAt,
            Instant submittedAt, Instant executionPlannedAt, Instant executionStartedAt, boolean executionStartUnknown,
            boolean executionStartInferred, java.util.UUID executionPlanId,
            com.flowops.domain.request.RequestOutcome outcome, Instant approvedAt, Instant rejectedAt,
            Instant executedAt, Instant cancelledAt, Instant expiredAt, Instant closedAt) {
        this(id, typeCode, typeName, departmentCode, status, payload, version, requesterId, requesterUsername,
                requesterDisplayName, correlationId, createdAt, updatedAt, submittedAt, executionPlannedAt,
                executionStartedAt, executionStartUnknown, executionStartInferred, executionPlanId, outcome, approvedAt,
                rejectedAt, executedAt, cancelledAt, expiredAt, closedAt, ExecutionProgress.unplanned(status, outcome));
    }

    public RequestListItem withExecutionProgress(ExecutionProgress progress) {
        return new RequestListItem(id, typeCode, typeName, departmentCode, status, payload, version, requesterId,
                requesterUsername, requesterDisplayName, correlationId, createdAt, updatedAt, submittedAt,
                executionPlannedAt, executionStartedAt, executionStartUnknown, executionStartInferred, executionPlanId,
                outcome, approvedAt, rejectedAt, executedAt, cancelledAt, expiredAt, closedAt,
                java.util.Objects.requireNonNull(progress));
    }

    public RequestListItem(Long id, String typeCode, String typeName, String departmentCode, RequestStatus status,
            Map<String, Object> payload, Long version, Long requesterId, String requesterUsername,
            String requesterDisplayName, String correlationId, Instant createdAt, Instant updatedAt,
            Instant submittedAt) {
        this(id, typeCode, typeName, departmentCode, status, payload, version, requesterId, requesterUsername,
                requesterDisplayName, correlationId, createdAt, updatedAt, submittedAt, null, null, false, false, null,
                null, null, null, null, null, null, null);
    }
}
