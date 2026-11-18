package com.flowops.action;

import com.flowops.application.execution.port.ProviderCalls;
import com.flowops.domain.execution.*;
import com.flowops.domain.execution.port.ActionHandler;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bounded, separate execution/lookup bulkheads. A cancelled future never frees a still-running thread. */
@Component
public class BoundedProviderCalls implements ProviderCalls {
    private final ActionHandler handler;
    private final Clock clock;
    private final ThreadPoolExecutor executions;
    private final ThreadPoolExecutor lookups;
    public BoundedProviderCalls(ActionHandler handler,Clock clock,
            @Value("${flowops.execution.provider-execution-slots:8}") int executionSlots,
            @Value("${flowops.execution.provider-lookup-slots:4}") int lookupSlots) {
        this.handler=handler;this.clock=clock;executions=pool(executionSlots,"flowops-provider-execute");lookups=pool(lookupSlots,"flowops-provider-lookup");
    }
    private ThreadPoolExecutor pool(int slots,String name) {
        if(slots<1 || slots>64) throw new IllegalArgumentException("Provider slots must be 1–64");
        return new ThreadPoolExecutor(slots,slots,0,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{var t=new Thread(r,name);t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    @Override public Map<String,Object> execute(ActionCommand command,ProviderContract contract,Instant deadline) {
        return invoke(executions,contract,command.action(),deadline,contract.executionTimeout(),call->handler.execute(command,call));
    }
    @Override public ProviderObservation reconcile(ProviderQuery query,ProviderContract contract,Instant deadline) {
        if(!contract.lookupSupported()) return ProviderObservation.unknown();
        return invoke(lookups,contract,query.action(),deadline,contract.reconciliationTimeout(),call->Objects.requireNonNull(handler.reconcile(query,call)));
    }
    private <T> T invoke(ThreadPoolExecutor pool,ProviderContract contract,String action,Instant durableDeadline,Duration declared,
                          java.util.function.Function<ProviderCall,T> work) {
        if(Thread.currentThread().isInterrupted()) throw new ProviderCallFailure(ProviderCallFailure.Reason.INTERRUPTED);
        if(!contract.equals(handler.contract(action))) throw new ProviderCallFailure(ProviderCallFailure.Reason.CONTRACT_CHANGED);
        var now=clock.instant();var remaining=Duration.between(now,durableDeadline);
        var budget=remaining.compareTo(declared)<0?remaining:declared;
        if(budget.isNegative() || budget.isZero()) throw new ProviderCallFailure(ProviderCallFailure.Reason.TIMEOUT);
        var call=new ProviderCall(contract,now.plus(budget),budget);var context=MDC.getCopyOfContextMap();
        Future<T> task;long began=System.nanoTime();
        // Queueing delay is not hidden: no queued work is permitted behind stuck calls.
        try {task=pool.submit(()->{if(context!=null) MDC.setContextMap(context);try{return work.apply(call);}finally{MDC.clear();}});}
        catch(RejectedExecutionException full) {throw new ProviderCallFailure(ProviderCallFailure.Reason.CAPACITY);}
        try {return task.get(Math.max(0,budget.toNanos()-(System.nanoTime()-began)),TimeUnit.NANOSECONDS);}
        catch(TimeoutException expired) {task.cancel(true);throw new ProviderCallFailure(ProviderCallFailure.Reason.TIMEOUT);}
        catch(InterruptedException interrupted) {task.cancel(true);Thread.currentThread().interrupt();throw new ProviderCallFailure(ProviderCallFailure.Reason.INTERRUPTED);}
        catch(ExecutionException failed) {
            var cause=failed.getCause();
            var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
            for(Throwable t=cause;t!=null && seen.add(t);t=t.getCause()) {
                if(t instanceof InterruptedException) throw new ProviderCallFailure(ProviderCallFailure.Reason.INTERRUPTED);
                if(t instanceof TimeoutException || t instanceof java.net.SocketTimeoutException || t instanceof java.net.http.HttpTimeoutException)
                    throw new ProviderCallFailure(ProviderCallFailure.Reason.TIMEOUT);
                if(t instanceof java.io.InterruptedIOException || t instanceof CancellationException)
                    throw new ProviderCallFailure(ProviderCallFailure.Reason.INTERRUPTED);
            }
            if(cause instanceof RuntimeException runtime) throw runtime;
            throw new ActionHandler.ActionExecutionException("Provider invocation failed",cause);
        }
    }
    @Override public Capacity capacity() {return new Capacity(executions.getMaximumPoolSize(),executions.getActiveCount(),lookups.getMaximumPoolSize(),lookups.getActiveCount());}
    @PreDestroy public void shutdown() {executions.shutdownNow();lookups.shutdownNow();}
}
