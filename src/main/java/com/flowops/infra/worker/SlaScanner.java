package com.flowops.infra.worker;
import com.flowops.application.service.OutboxService;

import com.flowops.domain.Request;
import com.flowops.domain.port.RequestRepository;
import com.flowops.domain.RequestStatus;
import com.flowops.domain.StepInstance;
import com.flowops.domain.port.StepInstanceRepository;
import com.flowops.domain.StepState;
import com.flowops.domain.User;
import com.flowops.domain.port.UserRepository;
import com.flowops.domain.WorkflowStep;
import com.flowops.observability.FlowOpsSpans;
import com.flowops.observability.MetricNames;
import com.flowops.observability.SpanNames;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * SLA scanner (PLAN §8, iteration 7).
 *
 * <p>Periodically checks PENDING step instances whose step declares
 * {@code sla_minutes}:
 *
 * <ul>
 *   <li><b>Breach</b> (now &gt; createdAt + slaMinutes): sends an escalation
 *       notification via the outbox to users holding the step's
 *       {@code escalation_role} (or {@code required_role} as fallback).
 *       Idempotent via the outbox key {@code req:{id}:sla:{recipient}}.</li>
 *   <li><b>Expiry</b> (now &gt; createdAt + slaMinutes × 2): transitions the
 *       request to the terminal EXPIRED state.</li>
 * </ul>
 */
@Component
public class SlaScanner {

    private static final Logger log = LoggerFactory.getLogger(SlaScanner.class);

    private final StepInstanceRepository stepInstances;
    private final RequestRepository requests;
    private final UserRepository users;
    private final OutboxService outbox;
    private final MeterRegistry metrics;
    private final FlowOpsSpans spans;

    public SlaScanner(StepInstanceRepository stepInstances,
                      RequestRepository requests,
                      UserRepository users,
                      OutboxService outbox,
                      MeterRegistry metrics,
                      FlowOpsSpans spans) {
        this.stepInstances = stepInstances;
        this.requests = requests;
        this.users = users;
        this.outbox = outbox;
        this.metrics = metrics;
        this.spans = spans;
    }

    private volatile boolean running = true;

    public void setRunning(boolean running) {
        this.running = running;
    }

    public boolean isRunning() {
        return running;
    }

    @Scheduled(fixedDelay = WorkerDefaults.SLA_TICK_DELAY_MS)
    public void scheduledTick() {
        if (running) {
            scan();
        }
    }

    /**
     * Scan all PENDING step instances for SLA breach / expiry.
     * Called by the scheduler (when running) or directly by tests.
     */
    @Transactional
    public void scan() {
        io.opentelemetry.api.trace.Span span =
                spans.start(SpanNames.SLA_SCAN, Map.of("component", "sla-scanner"));
        try {
            doScan(span);
        } catch (RuntimeException e) {
            spans.error(span, e);
            throw e;
        }
    }

    private void doScan(io.opentelemetry.api.trace.Span span) {
        List<StepInstance> pending = stepInstances.findByStateWithAssociations(StepState.PENDING);
        if (pending.isEmpty()) {
            spans.success(span);
            return;
        }

        Instant now = Instant.now();
        int breaches = 0;
        int expiries = 0;

        for (StepInstance si : pending) {
            WorkflowStep step = si.getStep();
            if (step.getSlaMinutes() == null) {
                continue; // no SLA declared
            }

            Request request = si.getRequest();
            if (request.getStatus().isTerminal()) {
                continue; // already terminal — nothing to do
            }

            Instant slaDeadline = si.getCreatedAt().plus(Duration.ofMinutes(step.getSlaMinutes()));
            if (now.isBefore(slaDeadline)) {
                continue; // within SLA window
            }

            // ── SLA breach: notify escalation role ──────────────────────
            String escalationRole = step.getEscalationRole() != null
                    ? step.getEscalationRole()
                    : step.getRequiredRole();

            List<User> escalators = users.findActiveByRoleCode(escalationRole);
            String message = "SLA BREACH: Request #" + request.getId()
                    + " step " + step.getCode() + " (" + step.getName()
                    + ") is past its " + step.getSlaMinutes() + "min SLA. Please review.";

            for (User esc : escalators) {
                // Exclude the requester (they already know their request is stuck)
                if (esc.getId().equals(request.getRequester().getId())) {
                    continue;
                }
                outbox.notifySlaBreach(request.getId(), esc.getId(), step.getCode(), message, request.getCorrelationId());
                metrics.counter(MetricNames.SLA_BREACH, "step", step.getCode()).increment();
                breaches++;
            }

            // ── Expiry: 2× SLA → terminal ───────────────────────────────
            Instant expiryDeadline = si.getCreatedAt()
                    .plus(Duration.ofMinutes(step.getSlaMinutes() * WorkerDefaults.SLA_EXPIRY_MULTIPLIER));
            if (now.isAfter(expiryDeadline)) {
                request.transitionTo(RequestStatus.EXPIRED);
                requests.save(request);
                metrics.counter(MetricNames.SLA_EXPIRED, "step", step.getCode()).increment();
                expiries++;
                log.info("REQUEST EXPIRED: id={} step={} sla={}min", request.getId(), step.getCode(), step.getSlaMinutes());
            }
        }

        if (breaches > 0 || expiries > 0) {
            log.info("SLA SCAN: {} breach notification(s), {} expir(y/ies)", breaches, expiries);
        }
        span.setAttribute("sla.breaches", breaches);
        span.setAttribute("sla.expiries", expiries);
        spans.success(span);
    }
}
