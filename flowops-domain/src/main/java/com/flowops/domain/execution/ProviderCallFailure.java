package com.flowops.domain.execution;

/** A bounded caller stopped waiting; cancellation is not evidence that external effects stopped. */
public class ProviderCallFailure extends RuntimeException {
    public enum Reason { TIMEOUT, INTERRUPTED, CAPACITY, CONTRACT_CHANGED }
    private final Reason reason;
    public ProviderCallFailure(Reason reason) { super("Provider call requires reconciliation: "+reason);this.reason=reason; }
    public Reason reason() { return reason; }
}
