package com.flowops.domain.exception;

/**
 * Thrown by aggregate state machines when an operation is illegal in the current state
 * (e.g. satisfying an already SATISFIED step, closing a DRAFT request, completing a
 * DEAD_LETTER job).
 *
 * <p>
 * This is a domain-level invariant backstop: application services normally check state
 * first (and surface a friendlier {@link ConflictException}), so reaching this exception
 * means the model's own rules were about to be violated. Mapped to HTTP 409.
 */
public class InvalidStateTransitionException extends IllegalStateException {

    /** Transition form: {@code from} → {@code to}. */
    public InvalidStateTransitionException(String aggregate, Object from, Object to) {
        super("Illegal " + aggregate + " state transition: " + from + " -> " + to);
    }

    /** Operation form: {@code operation} not allowed while in state {@code from}. */
    public InvalidStateTransitionException(String aggregate, Object from, String operation) {
        super("Illegal " + aggregate + " operation '" + operation + "' in state " + from);
    }

}
