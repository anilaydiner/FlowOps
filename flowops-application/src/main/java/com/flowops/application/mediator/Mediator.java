package com.flowops.application.mediator;

public interface Mediator {
    <R> R send(Message<R> message);
}
