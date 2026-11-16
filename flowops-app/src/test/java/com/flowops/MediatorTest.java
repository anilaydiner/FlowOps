package com.flowops;

import com.flowops.application.mediator.*;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MediatorTest {
    record Echo(String value) implements Message<String> {}
    record Unknown() implements Message<Integer> {}

    static class EchoHandler implements MessageHandler<Echo, String> {
        public Class<Echo> messageType() { return Echo.class; }
        public String handle(Echo message) { return message.value(); }
    }

    @Test
    void dispatchesEachMessageAndReturnsTypedResult() {
        Mediator mediator = new SynchronousMediator(List.of(new EchoHandler()));
        String first = mediator.send(new Echo("first"));
        assertEquals("first", first);
        assertEquals("second", mediator.send(new Echo("second")));
    }

    @Test
    void duplicateHandlersFailAtConstruction() {
        assertThrows(IllegalStateException.class,
                () -> new SynchronousMediator(List.of(new EchoHandler(), new EchoHandler())));
    }

    @Test
    void missingHandlerAndNullMessageFailExplicitly() {
        Mediator mediator = new SynchronousMediator(List.of(new EchoHandler()));
        assertThrows(IllegalArgumentException.class, () -> mediator.send(new Unknown()));
        assertThrows(NullPointerException.class, () -> mediator.send(null));
    }

    @Test
    void propagatesOriginalException() {
        RuntimeException failure = new IllegalStateException("handler failure");
        EchoHandler handler = new EchoHandler() {
            @Override public String handle(Echo message) { throw failure; }
        };
        Mediator mediator = new SynchronousMediator(List.of(handler));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> mediator.send(new Echo("test"))));
    }

    public static class TransactionalHandler extends EchoHandler {
        @Override
        @Transactional(readOnly = true)
        public String handle(Echo message) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return super.handle(message);
        }
    }

    @Test
    void dispatchRetainsSpringProxyAndReadOnlyTransaction() {
        TestTransactionManager transactions = new TestTransactionManager();
        ProxyFactory factory = new ProxyFactory(new TransactionalHandler());
        factory.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        MessageHandler<?, ?> proxy = (MessageHandler<?, ?>) factory.getProxy();
        Mediator mediator = new SynchronousMediator(List.of(proxy));
        assertEquals("inside transaction", mediator.send(new Echo("inside transaction")));
        assertTrue(transactions.committed);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    static class TestTransactionManager extends AbstractPlatformTransactionManager {
        boolean committed;
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object transaction, TransactionDefinition definition) {}
        protected void doCommit(DefaultTransactionStatus status) { committed = true; }
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
