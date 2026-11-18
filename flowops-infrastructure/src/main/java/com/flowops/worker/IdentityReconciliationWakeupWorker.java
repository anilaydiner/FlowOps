package com.flowops.worker;

import com.flowops.application.request.port.IdentityReconciliationWakeup;
import com.flowops.application.request.recovery.DrainIdentityReconciliation;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.*;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;

/** Identity transactions release their connections before asynchronous workflow locking. */
@Component
public class IdentityReconciliationWakeupWorker implements IdentityReconciliationWakeup, DisposableBean {
    private static final Logger log=LoggerFactory.getLogger(IdentityReconciliationWakeupWorker.class);
    private final DrainIdentityReconciliation drain;
    private final AtomicBoolean pending=new AtomicBoolean();
    private volatile boolean running=true;
    private final ExecutorService executor=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(1),task->{
        Thread thread=new Thread(task,"identity-reconciliation");thread.setDaemon(true);return thread;
    });
    public IdentityReconciliationWakeupWorker(DrainIdentityReconciliation drain) { this.drain=drain; }
    public void setRunning(boolean value) { running=value; }
    @Override public void request() {
        if(!running || !pending.compareAndSet(false,true)) return;
        try { executor.execute(()->{
            try { drain.drain(); }
            catch(RuntimeException failure) { log.error("Immediate reconciliation failed; durable hints remain queued",failure); }
            finally { pending.set(false); }
        }); } catch(RejectedExecutionException shutdown) { pending.set(false); }
    }
    @Override public void destroy() { running=false;executor.shutdownNow(); }
}
