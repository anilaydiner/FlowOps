package com.flowops.persistence.mapping;

import com.flowops.domain.request.Decision;
import com.flowops.persistence.entity.DecisionEntity;

/**
 * Maps the {@link Decision} aggregate to and from its JPA entity. Stateless — safe to
 * call from any thread.
 *
 * <p>
 * {@code fromEntity} runs inside the persistence adapter (i.e. inside the transaction),
 * so lazy associations are safely resolved here and the resulting domain object needs no
 * live persistence context.
 */
public final class DecisionMappers {

    private DecisionMappers() {
    }

    /** Domain → entity. Null-safe; nested aggregates are mapped recursively. */
    public static DecisionEntity toEntity(Decision d) {
        if (d == null) {
            return null;
        }
        DecisionEntity e = new DecisionEntity();
        e.setId(d.getId());
        e.setRequest(RequestMappers.toEntity(d.getRequest()));
        e.setStepInstance(StepInstanceMappers.toEntity(d.getStepInstance()));
        e.setActorId(d.getActorId());
        e.setActorUsername(d.getActorUsername());
        e.setAction(d.getAction());
        e.setJustification(d.getJustification());
        e.setEvidence(d.getEvidence());
        e.setDelegatedTo(d.getDelegatedTo());
        e.setCreatedAt(d.getCreatedAt());
        return e;
    }

    /** Entity → domain. Null-safe; nested aggregates are mapped recursively. */
    public static Decision fromEntity(DecisionEntity e) {
        if (e == null) {
            return null;
        }
        Decision d = new Decision();
        d.setId(e.getId());
        d.setRequest(RequestMappers.fromEntity(e.getRequest()));
        d.setStepInstance(StepInstanceMappers.fromEntity(e.getStepInstance()));
        d.setActorId(e.getActorId());
        d.setActorUsername(e.getActorUsername());
        d.setAction(e.getAction());
        d.setJustification(e.getJustification());
        d.setEvidence(e.getEvidence());
        d.setDelegatedTo(e.getDelegatedTo());
        d.setCreatedAt(e.getCreatedAt());
        return d;
    }

}
