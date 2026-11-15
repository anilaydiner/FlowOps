package com.flowops.application.usecase;

import com.flowops.application.port.SlaMetrics;
import com.flowops.application.notification.OutboxService;
import com.flowops.domain.Request;
import com.flowops.domain.RequestStatus;
import com.flowops.domain.StepInstance;
import com.flowops.domain.StepState;
import com.flowops.domain.User;
import com.flowops.domain.WorkflowStep;
import com.flowops.domain.port.RequestRepository;
import com.flowops.domain.port.StepInstanceRepository;
import com.flowops.domain.port.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Use case: scan PENDING step instances for SLA breach / expiry
 * (PLAN §8, iteration 7).
 *
 * <p>The policy lives on the aggregate — breach at
 * {@code createdAt + slaMinutes}, expiry at
 * {@code createdAt + slaMinutes × 2} (see {@link StepInstance#isSlaBreached},
 * {@link StepInstance#isExpired}). For each breached step the active users
 * holding the step's escalation role (or required role as fallback) are
 * notified via the transactional outbox, excluding the requester.
 * Idempotent via the outbox key {@code req:{id}:sla:{recipient}}.
 * Expired steps transition the request to the terminal EXPIRED state.</p>
 */
@Service
public class ScanSla {

    private static final Logger log = LoggerFactory.getLogger(ScanSla.class);

    private final StepInstanceRepository stepInstances;
    private final RequestRepository requests;
    private final UserRepository users;
    private final OutboxService outbox;
    private final SlaMetrics slaMetrics;

    public ScanSla(StepInstanceRepository stepInstances,
                   RequestRepository requests,
                   UserRepository users,
                   OutboxService outbox,
                   SlaMetrics slaMetrics) {
        this.stepInstances = stepInstances;
        this.requests = requests;
        this.users = users;
        this.outbox = outbox;
        this.slaMetrics = slaMetrics;
    }

    /** Outcome of one scan: breach notifications sent, requests expired. */
    public record Result(int breaches, int expiries) {}

    @Transactional
    public Result scan() {
        List<StepInstance> pending = stepInstances.findByStateWithAssociations(StepState.PENDING);
        if (pending.isEmpty()) {
            return new Result(0, 0);
        }

        Instant now = Instant.now();
        int breaches = 0;
        int expiries = 0;

        for (StepInstance si : pending) {
            if (!si.isSlaBreached(now)) {
                continue; // no SLA declared, or still within the window
            }

            Request request = si.getRequest();
            if (request.getStatus().isTerminal()) {
                continue; // already terminal — nothing to do
            }

            WorkflowStep step = si.getStep();

            // ── SLA breach: notify escalation role ──────────────────────
            List<User> escalators = users.findActiveByRoleCode(si.escalationRole());
            String message = "SLA BREACH: Request #" + request.getId()
                    + " step " + step.getCode() + " (" + step.getName()
                    + ") is past its " + step.getSlaMinutes() + "min SLA. Please review.";

            for (User esc : escalators) {
                // Exclude the requester (they already know their request is stuck)
                if (esc.getId().equals(request.getRequester().getId())) {
                    continue;
                }
                outbox.notifySlaBreach(request.getId(), esc.getId(), step.getCode(), message, request.getCorrelationId());
                slaMetrics.recordBreach(step.getCode());
                breaches++;
            }

            // ── Expiry: 2× SLA → terminal ───────────────────────────────
            if (si.isExpired(now)) {
                request.transitionTo(RequestStatus.EXPIRED);
                requests.save(request);
                slaMetrics.recordExpiry(step.getCode());
                expiries++;
                log.info("REQUEST EXPIRED: id={} step={} sla={}min", request.getId(), step.getCode(), step.getSlaMinutes());
            }
        }

        if (breaches > 0 || expiries > 0) {
            log.info("SLA SCAN: {} breach notification(s), {} expir(y/ies)", breaches, expiries);
        }
        return new Result(breaches, expiries);
    }
}
