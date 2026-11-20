package com.flowops.persistence.mapping;

import com.flowops.domain.request.Request;
import com.flowops.persistence.entity.RequestEntity;

/**
 * Maps the {@link Request} aggregate to and from its JPA entity. Stateless — safe to call
 * from any thread.
 *
 * <p>
 * {@code fromEntity} runs inside the persistence adapter (i.e. inside the transaction),
 * so lazy associations are safely resolved here and the resulting domain object needs no
 * live persistence context.
 */
public final class RequestMappers {

    private RequestMappers() {
    }

    /** Domain → entity. Null-safe; nested aggregates are mapped recursively. */
    public static RequestEntity toEntity(Request d) {
        if (d == null) {
            return null;
        }
        RequestEntity e = new RequestEntity();
        e.setId(d.getId());
        e.setType(RequestTypeMappers.toEntity(d.getType()));
        e.setRequester(UserMappers.toEntity(d.getRequester()));
        e.setStatus(d.getStatus());
        e.setPayload(d.getPayload());
        e.setVersion(d.getVersion());
        e.setCorrelationId(d.getCorrelationId());
        e.setCreatedAt(d.getCreatedAt());
        e.setUpdatedAt(d.getUpdatedAt());
        e.setSubmittedAt(d.getSubmittedAt());
        e.setLegacyPlanDispositionId(d.getLegacyPlanDispositionId());
        e.setExecutionPlanId(d.getExecutionPlanId());
        e.setExecutionPlannedAt(d.getExecutionPlannedAt());
        e.setExecutionStartedAt(d.getExecutionStartedAt());
        e.setExecutionStartUnknown(d.isExecutionStartUnknown());
        e.setExecutionStartInferred(d.isExecutionStartInferred());
        e.setOutcome(d.getOutcome());
        e.setApprovedAt(d.getApprovedAt());
        e.setRejectedAt(d.getRejectedAt());
        e.setExecutedAt(d.getExecutedAt());
        e.setCancelledAt(d.getCancelledAt());
        e.setExpiredAt(d.getExpiredAt());
        e.setClosedAt(d.getClosedAt());
        return e;
    }

    /** Entity → domain. Null-safe; nested aggregates are mapped recursively. */
    public static Request fromEntity(RequestEntity e) {
        if (e == null) {
            return null;
        }
        return Request.restore(new Request.Snapshot(e.getId(), RequestTypeMappers.fromEntity(e.getType()),
                UserMappers.fromEntity(e.getRequester()), e.getStatus(), e.getPayload(), e.getVersion(),
                e.getCorrelationId(), e.getCreatedAt(), e.getUpdatedAt(), e.getSubmittedAt(), e.getExecutionPlannedAt(),
                e.getExecutionStartedAt(), e.isExecutionStartUnknown(), e.isExecutionStartInferred(),
                e.getExecutionPlanId(), e.getOutcome(), e.getApprovedAt(), e.getRejectedAt(), e.getExecutedAt(),
                e.getCancelledAt(), e.getExpiredAt(), e.getClosedAt(), e.getLegacyPlanDispositionId()));
    }

}
