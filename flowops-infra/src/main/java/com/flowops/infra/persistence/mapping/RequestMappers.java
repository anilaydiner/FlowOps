package com.flowops.infra.persistence.mapping;

import com.flowops.domain.Request;
import com.flowops.infra.persistence.entity.RequestEntity;
import com.flowops.infra.persistence.entity.RequestTypeEntity;
import com.flowops.infra.persistence.entity.UserEntity;

/**
 * Maps the {@link Request} aggregate to and from its JPA entity.
 * Stateless — safe to call from any thread.
 *
 * <p>{@code fromEntity} runs inside the persistence adapter (i.e. inside the
 * transaction), so lazy associations are safely resolved here and the
 * resulting domain object needs no live persistence context.
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
        return e;
    }

    /** Entity → domain. Null-safe; nested aggregates are mapped recursively. */
    public static Request fromEntity(RequestEntity e) {
        if (e == null) {
            return null;
        }
        Request d = new Request();
        d.setId(e.getId());
        d.setType(RequestTypeMappers.fromEntity(e.getType()));
        d.setRequester(UserMappers.fromEntity(e.getRequester()));
        d.setStatus(e.getStatus());
        d.setPayload(e.getPayload());
        d.setVersion(e.getVersion());
        d.setCorrelationId(e.getCorrelationId());
        d.setCreatedAt(e.getCreatedAt());
        d.setUpdatedAt(e.getUpdatedAt());
        d.setSubmittedAt(e.getSubmittedAt());
        return d;
    }
}
