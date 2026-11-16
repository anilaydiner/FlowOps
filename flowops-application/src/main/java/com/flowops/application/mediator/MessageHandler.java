package com.flowops.application.mediator;

/** One synchronous handler per concrete message type. */
public interface MessageHandler<M extends Message<R>, R> {
    // Explicit registration works with Spring proxies without generic-type reflection.
    Class<M> messageType();

    R handle(M message);
}
