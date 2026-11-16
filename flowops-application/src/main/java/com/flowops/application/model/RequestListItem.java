package com.flowops.application.model;

import com.flowops.domain.RequestStatus;

import java.time.Instant;
import java.util.Map;

/** Scalar request header for list, queue and detail reads; no aggregate or persistence context is required. */
public record RequestListItem(
        Long id, String typeCode, String typeName, String departmentCode,
        RequestStatus status, Map<String, Object> payload, Long version,
        Long requesterId, String requesterUsername, String requesterDisplayName,
        String correlationId, Instant createdAt, Instant updatedAt, Instant submittedAt
) {
}
