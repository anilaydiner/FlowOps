package com.flowops.application.mediator;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Dispatches on the caller's thread, preserving handler proxies, transactions and exceptions. */
@Component
public class SynchronousMediator implements Mediator {
    private final Map<Class<?>, MessageHandler<?, ?>> handlers;

    public SynchronousMediator(List<MessageHandler<?, ?>> handlers) {
        Map<Class<?>, MessageHandler<?, ?>> registry = new HashMap<>();
        for (MessageHandler<?, ?> handler : handlers) {
            Class<?> type = Objects.requireNonNull(handler.messageType(), "messageType");
            if (registry.putIfAbsent(type, handler) != null) {
                throw new IllegalStateException("Multiple handlers for " + type.getName());
            }
        }
        this.handlers = Map.copyOf(registry);
    }

    @Override
    public <R> R send(Message<R> message) {
        Objects.requireNonNull(message, "message");
        MessageHandler<?, ?> handler = handlers.get(message.getClass());
        if (handler == null) {
            throw new IllegalArgumentException("No handler for " + message.getClass().getName());
        }
        return dispatch(handler, message);
    }

    @SuppressWarnings("unchecked")
    private static <R> R dispatch(MessageHandler<?, ?> handler, Message<R> message) {
        // Registration binds M to its Class<M>; lookup uses that exact concrete class.
        return ((MessageHandler<Message<R>, R>) handler).handle(message);
    }
}
