package com.flowops.domain.execution;

import java.util.Map;
import java.util.Objects;

/** Trusted adapter evidence about the original logical action, never an instruction to execute it. */
public record ProviderObservation(Outcome outcome, String evidenceReference, Map<String,Object> result) {
    public enum Outcome {
        SUCCEEDED,
        /** No effect exists; the provider also fences late/in-flight old calls so one replay is safe. */
        SAFE_TO_RETRY,
        IN_PROGRESS,
        UNKNOWN
    }
    public ProviderObservation {
        Objects.requireNonNull(outcome,"outcome");
        if ((outcome==Outcome.SUCCEEDED || outcome==Outcome.SAFE_TO_RETRY)
                && (evidenceReference==null || evidenceReference.isBlank()))
            throw new IllegalArgumentException("Authoritative provider evidence is required");
        if(evidenceReference!=null && evidenceReference.length()>1000)
            throw new IllegalArgumentException("Provider evidence reference is too long");
        result=ActionCommand.freeze(result==null?Map.of():result);
    }
    public static ProviderObservation unknown() { return new ProviderObservation(Outcome.UNKNOWN,null,Map.of()); }
}
