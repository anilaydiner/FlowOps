package com.flowops.worker;

import com.flowops.application.execution.HeartbeatExecutionJob;
import com.flowops.application.execution.port.*;
import com.flowops.domain.execution.ExecutionLease;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.*;
import org.springframework.stereotype.Component;

/** Independent from Spring's job-polling scheduler, which may be occupied by the provider call. */
@Component
public class ExecutionHeartbeatScheduler implements ExecutionHeartbeats {
    private static final Logger log=LoggerFactory.getLogger(ExecutionHeartbeatScheduler.class);
    private final ScheduledThreadPoolExecutor scheduler;
    private final HeartbeatExecutionJob heartbeat;
    private final long intervalMillis;
    private final java.time.Clock clock;
    public ExecutionHeartbeatScheduler(HeartbeatExecutionJob heartbeat,ExecutionHeartbeatSettings settings,java.time.Clock clock) {
        this.heartbeat=heartbeat;this.clock=clock;this.intervalMillis=settings.heartbeatInterval().toMillis();
        if(intervalMillis<1) throw new IllegalArgumentException("Heartbeat interval must be positive");
        scheduler=new ScheduledThreadPoolExecutor(2,runnable->{
            var thread=new Thread(runnable,"flowops-execution-heartbeat");thread.setDaemon(true);return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
    }
    @Override public Registration start(ExecutionLease claim,java.time.Instant deadline) {
        if(deadline==null || !clock.instant().isBefore(deadline)) throw new IllegalStateException("Attempt deadline passed before handler invocation");
        var registration=new RenewalTask(claim,deadline);
        registration.future=scheduler.scheduleWithFixedDelay(registration,intervalMillis,intervalMillis,TimeUnit.MILLISECONDS);
        if(registration.closed.get()) registration.future.cancel(false);
        return registration;
    }
    private final class RenewalTask implements Runnable,Registration {
        private final ExecutionLease claim;
        private final java.time.Instant deadline;
        private final AtomicBoolean closed=new AtomicBoolean();
        private volatile ScheduledFuture<?> future;
        private RenewalTask(ExecutionLease claim,java.time.Instant deadline) { this.claim=claim;this.deadline=deadline; }
        @Override public void run() {
            if(closed.get()) return;
            if(!clock.instant().isBefore(deadline)) { close();return; }
            try {
                if(heartbeat.renew(claim)==HeartbeatExecutionJob.Renewal.LOST) close();
            } catch(RuntimeException failure) {
                // No replay or guessed lease on storage failure. Retry while the persisted lease is still valid.
                log.warn("Execution heartbeat failed for job {}; persisted lease remains authoritative",claim.jobId(),failure);
            }
        }
        @Override public void close() {
            closed.set(true);var current=future;if(current!=null) current.cancel(false);
        }
    }
    @PreDestroy public void shutdown() { scheduler.shutdown(); }
}
