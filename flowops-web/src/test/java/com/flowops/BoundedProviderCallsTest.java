package com.flowops;

import com.flowops.action.BoundedProviderCalls;
import com.flowops.domain.execution.*;
import com.flowops.domain.execution.port.ActionHandler;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BoundedProviderCallsTest {
    private final ActionHandler handler=mock(ActionHandler.class);
    private final ActionCommand command=new ActionCommand("stable-key",1L,"ACTION",Map.of("frozen",true),"correlation");
    private final ProviderQuery query=new ProviderQuery("stable-key",1L,"ACTION");
    private ProviderContract contract(long execution,long lookup) {
        return new ProviderContract("provider-a","v1",ProviderContract.Transport.LOCAL,Duration.ZERO,Duration.ZERO,
                Duration.ofMillis(execution),Duration.ofMillis(lookup),true,true,false);
    }
    private BoundedProviderCalls calls(ProviderContract c) {when(handler.contract("ACTION")).thenReturn(c);return new BoundedProviderCalls(handler,Clock.systemUTC(),1,1);}
    private void stubborn(CountDownLatch release) {
        boolean done=false;while(!done) try {release.await();done=true;}catch(InterruptedException ignored) {}
    }
    @Test void invocationReceivesItsBudgetAndCorrelationOnADifferentThread() {
        var c=contract(1000,500);var calls=calls(c);var caller=Thread.currentThread();MDC.put("correlationId","test-correlation");
        try {
            when(handler.execute(any(),any())).thenAnswer(inv->{
                ProviderCall budget=inv.getArgument(1);assertEquals(c,budget.contract());assertTrue(budget.timeout().compareTo(c.executionTimeout())<=0);
                assertEquals("test-correlation",MDC.get("correlationId"));assertNotSame(caller,Thread.currentThread());
                assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());return Map.of("ok",true);
            });
            assertEquals(Map.of("ok",true),calls.execute(command,c,Instant.now().plusSeconds(2)));
        } finally {MDC.clear();calls.shutdown();}
    }
    @Test void timedOutUncooperativeExecutionKeepsItsSlotAndCannotStarveLookup() {
        var c=contract(500,500);var calls=calls(c);var release=new CountDownLatch(1);var entered=new CountDownLatch(1);
        when(handler.execute(any(),any())).thenAnswer(inv->{entered.countDown();stubborn(release);return Map.of("late",true);});
        when(handler.reconcile(any(),any())).thenReturn(ProviderObservation.unknown());
        try {
            assertEquals(ProviderCallFailure.Reason.TIMEOUT,assertThrows(ProviderCallFailure.class,()->calls.execute(command,c,Instant.now().plusSeconds(2))).reason());
            assertEquals(0,entered.getCount());assertEquals(1,calls.capacity().executing());
            assertEquals(ProviderCallFailure.Reason.CAPACITY,assertThrows(ProviderCallFailure.class,()->calls.execute(command,c,Instant.now().plusSeconds(2))).reason());
            assertEquals(ProviderObservation.Outcome.UNKNOWN,calls.reconcile(query,c,Instant.now().plusSeconds(2)).outcome());
            verify(handler,times(1)).execute(any(),any());
        } finally {release.countDown();calls.shutdown();}
    }
    @Test void lookupHasItsOwnTimeoutAndDoesNotConsumeExecutionCapacity() {
        var c=contract(500,300);var calls=calls(c);var release=new CountDownLatch(1);
        when(handler.reconcile(any(),any())).thenAnswer(inv->{stubborn(release);return ProviderObservation.unknown();});
        when(handler.execute(any(),any())).thenReturn(Map.of("ok",true));
        try {
            assertEquals(ProviderCallFailure.Reason.TIMEOUT,assertThrows(ProviderCallFailure.class,()->calls.reconcile(query,c,Instant.now().plusSeconds(2))).reason());
            assertEquals(1,calls.capacity().lookingUp());assertEquals(Map.of("ok",true),calls.execute(command,c,Instant.now().plusSeconds(2)));
        } finally {release.countDown();calls.shutdown();}
    }
    @Test void transportTimeoutIsUncertainAndDoesNotLeakProviderExceptionText() {
        var c=contract(1000,500);var calls=calls(c);
        when(handler.execute(any(),any())).thenThrow(new ActionHandler.ActionExecutionException("private provider error",new java.net.SocketTimeoutException("secret")));
        try {var failure=assertThrows(ProviderCallFailure.class,()->calls.execute(command,c,Instant.now().plusSeconds(2)));
            assertEquals(ProviderCallFailure.Reason.TIMEOUT,failure.reason());assertFalse(failure.getMessage().contains("secret"));}
        finally {calls.shutdown();}
    }
    @Test void providerCancellationDoesNotAuthorizeAnAutomaticReplay() {
        var c=contract(1000,500);var calls=calls(c);
        when(handler.execute(any(),any())).thenThrow(new CancellationException("private provider cancellation"));
        try {assertEquals(ProviderCallFailure.Reason.INTERRUPTED,assertThrows(ProviderCallFailure.class,()->calls.execute(command,c,Instant.now().plusSeconds(2))).reason());}
        finally {calls.shutdown();}
    }
    @Test void interruptionAndElapsedDurableDeadlinePreventInvocation() {
        var c=contract(1000,500);var calls=calls(c);
        try {
            Thread.currentThread().interrupt();
            assertEquals(ProviderCallFailure.Reason.INTERRUPTED,assertThrows(ProviderCallFailure.class,()->calls.execute(command,c,Instant.now().plusSeconds(2))).reason());
            assertTrue(Thread.currentThread().isInterrupted());Thread.interrupted();
            assertEquals(ProviderCallFailure.Reason.TIMEOUT,assertThrows(ProviderCallFailure.class,()->calls.execute(command,c,Instant.now().minusSeconds(1))).reason());
            verify(handler,never()).execute(any(),any());
        } finally {Thread.interrupted();calls.shutdown();}
    }
    @Test void changedContractCannotInvokeThePreviouslyAuthorizedProvider() {
        var c=contract(1000,500);var calls=calls(c);when(handler.contract("ACTION")).thenReturn(contract(999,500));
        try {assertEquals(ProviderCallFailure.Reason.CONTRACT_CHANGED,assertThrows(ProviderCallFailure.class,()->calls.execute(command,c,Instant.now().plusSeconds(2))).reason());verify(handler,never()).execute(any(),any());}
        finally {calls.shutdown();}
    }
    @Test void adapterWithoutLookupReportsUnknownWithoutProviderIO() {
        var c=new ProviderContract("provider-a","v1",ProviderContract.Transport.LOCAL,Duration.ZERO,Duration.ZERO,Duration.ofSeconds(1),Duration.ofMillis(500),false,false,false);
        var calls=calls(c);
        try {assertEquals(ProviderObservation.Outcome.UNKNOWN,calls.reconcile(query,c,Instant.now().plusSeconds(2)).outcome());verify(handler,never()).reconcile(any(),any());}
        finally {calls.shutdown();}
    }
}
