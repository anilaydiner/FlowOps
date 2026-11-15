package com.flowops.infra.persistence.mapping;

import com.flowops.domain.Clarification;
import com.flowops.infra.persistence.entity.ClarificationEntity;

/**
 * Maps the {@link Clarification} aggregate to and from its JPA entity.
 * Stateless — safe to call from any thread.
 *
 * <p>{@code fromEntity} runs inside the persistence adapter (i.e. inside the
 * transaction), so lazy associations are safely resolved here and the
 * resulting domain object needs no live persistence context.
 */
public final class ClarificationMappers {

    private ClarificationMappers() {
    }

    /** Domain → entity. Null-safe; nested aggregates are mapped recursively. */
    public static ClarificationEntity toEntity(Clarification d) {
        if (d == null) {
            return null;
        }
        ClarificationEntity e = new ClarificationEntity();
        e.setId(d.getId());
        e.setRequestId(d.getRequestId());
        e.setAskedBy(d.getAskedBy());
        e.setQuestion(d.getQuestion());
        e.setAnswer(d.getAnswer());
        e.setAnsweredBy(d.getAnsweredBy());
        e.setAnsweredAt(d.getAnsweredAt());
        e.setCreatedAt(d.getCreatedAt());
        return e;
    }

    /** Entity → domain. Null-safe; nested aggregates are mapped recursively. */
    public static Clarification fromEntity(ClarificationEntity e) {
        if (e == null) {
            return null;
        }
        Clarification d = new Clarification();
        d.setId(e.getId());
        d.setRequestId(e.getRequestId());
        d.setAskedBy(e.getAskedBy());
        d.setQuestion(e.getQuestion());
        d.setAnswer(e.getAnswer());
        d.setAnsweredBy(e.getAnsweredBy());
        d.setAnsweredAt(e.getAnsweredAt());
        d.setCreatedAt(e.getCreatedAt());
        return d;
    }
}
