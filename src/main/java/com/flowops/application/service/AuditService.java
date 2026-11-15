package com.flowops.application.service;

import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.policy.AuthorizationEvaluator;
import com.flowops.domain.policy.PermissionCodes;
import com.flowops.domain.exception.NotFoundException;

import com.flowops.domain.AuditEvent;
import com.flowops.domain.Decision;
import com.flowops.domain.ExecutionJob;
import com.flowops.domain.OutboxMessage;
import com.flowops.domain.port.OutboxMessageRepository;
import com.flowops.domain.Request;
import com.flowops.domain.port.RequestRepository;
import com.flowops.domain.RequestStatus;
import com.flowops.domain.StepInstance;
import com.flowops.domain.port.StepInstanceRepository;
import com.flowops.domain.port.DecisionRepository;
import com.flowops.domain.port.ExecutionJobRepository;
import com.flowops.domain.identity.CallerIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Queryable audit timeline for a request (PLAN §8, iteration 8).
 *
 * <p>Aggregates every auditable event into one chronological list:
 * <ul>
 *   <li>{@code STATUS} — created and terminal transitions</li>
 *   <li>{@code SUBMITTED} — entered the workflow</li>
 *   <li>{@code DECISION} — APPROVE / REJECT / DELEGATE with actor + evidence</li>
 *   <li>{@code JOB} — execution job created, then finished (status + result)</li>
 *   <li>{@code SLA} — SLA breach escalations (from the outbox)</li>
 * </ul>
 *
 * <p>Filters: {@code from}/{@code to} (time window) and {@code type}
 * (entity filter). Access: owner or admin — same rules as the request API,
 * 404 for everyone else (no existence leak).
 *
 * <p>Read-only: the underlying tables are append-only (V9 triggers), so the
 * timeline is a faithful history, not a mutable view.
 */
@Service
public class AuditService {

    public static final String TYPE_STATUS = "STATUS";
    public static final String TYPE_SUBMITTED = "SUBMITTED";
    public static final String TYPE_DECISION = "DECISION";
    public static final String TYPE_JOB = "JOB";
    public static final String TYPE_SLA = "SLA";

    private final RequestRepository requests;
    private final StepInstanceRepository stepInstances;
    private final DecisionRepository decisions;
    private final ExecutionJobRepository executionJobs;
    private final OutboxMessageRepository outbox;
    private final AuthorizationEvaluator authz;

    public AuditService(RequestRepository requests,
                        StepInstanceRepository stepInstances,
                        DecisionRepository decisions,
                        ExecutionJobRepository executionJobs,
                        OutboxMessageRepository outbox,
                        AuthorizationEvaluator authz) {
        this.requests = requests;
        this.stepInstances = stepInstances;
        this.decisions = decisions;
        this.executionJobs = executionJobs;
        this.outbox = outbox;
        this.authz = authz;
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> timeline(Long requestId, Instant from, Instant to, String type, CallerIdentity caller) {
        Request request = requests.findById(requestId)
                .orElseThrow(() -> new NotFoundException("Request not found: " + requestId));
        boolean isOwn = caller.id().equals(request.getRequester().getId());
        if (isOwn) {
            authz.require(caller, PermissionCodes.AUDIT_READ);
        } else {
            authz.require(caller, PermissionCodes.AUDIT_READ_ANY);
        }

        List<AuditEvent> events = new ArrayList<>();

        // ── STATUS: creation ──────────────────────────────────────────
        events.add(AuditEvent.of(TYPE_STATUS, request.getCreatedAt(),
                request.getRequester().getUsername(),
                "Request created as " + request.getStatus(),
                Map.of("type", request.getType().getCode())));

        // ── SUBMITTED ─────────────────────────────────────────────────
        if (request.getSubmittedAt() != null) {
            events.add(AuditEvent.of(TYPE_SUBMITTED, request.getSubmittedAt(),
                    request.getRequester().getUsername(),
                    "Request submitted into workflow",
                    null));
        }

        // ── DECISION ──────────────────────────────────────────────────
        for (StepInstance si : stepInstances.findByRequestIdOrderById(requestId)) {
            for (Decision d : decisions.findByStepInstanceIdOrderById(si.getId())) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("step", si.getStep().getCode());
                details.put("action", d.getAction().name());
                if (d.getJustification() != null) details.put("justification", d.getJustification());
                if (d.getDelegatedTo() != null) details.put("delegatedTo", d.getDelegatedTo());
                details.put("evidence", d.getEvidence());
                events.add(AuditEvent.of(TYPE_DECISION, d.getCreatedAt(), d.getActorUsername(),
                        d.getAction().name() + " on step " + si.getStep().getCode(), details));
            }
        }

        // ── JOB (created + finished) ──────────────────────────────────
        for (ExecutionJob job : executionJobs.findByRequestIdOrderByCreatedAtAsc(requestId)) {
            events.add(AuditEvent.of(TYPE_JOB, job.getCreatedAt(), null,
                    "Execution job " + job.getAction() + " enqueued",
                    Map.of("action", job.getAction())));
            if (job.getCompletedAt() != null) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("action", job.getAction());
                details.put("status", job.getStatus().name());
                details.put("retryCount", job.getRetryCount());
                if (job.getResult() != null) details.put("result", job.getResult());
                if (job.getLastError() != null) details.put("lastError", job.getLastError());
                events.add(AuditEvent.of(TYPE_JOB, job.getCompletedAt(), null,
                        "Execution job " + job.getAction() + " finished: " + job.getStatus(),
                        details));
            }
        }

        // ── SLA breach escalations (outbox rows for this request) ─────
        for (OutboxMessage msg : outbox.findByKeyPrefix(OutboxMessage.KEY_PREFIX_REQUEST + requestId + ":sla")) {
            events.add(AuditEvent.of(TYPE_SLA, msg.getCreatedAt(), null,
                    "SLA breach escalation to user " + msg.getRecipientId()
                            + " (" + msg.getStatus() + ")",
                    Map.of("channel", msg.getChannel(),
                           "outboxStatus", msg.getStatus().name(),
                           "recipientId", msg.getRecipientId())));
        }

        // ── STATUS: reached state (if past review) ────────────────────
        RequestStatus status = request.getStatus();
        if (status == RequestStatus.APPROVED || status == RequestStatus.EXECUTED
                || status == RequestStatus.CLOSED || status == RequestStatus.REJECTED
                || status == RequestStatus.EXPIRED) {
            events.add(AuditEvent.of(TYPE_STATUS, request.getUpdatedAt(), null,
                    "Request is " + status, null));
        } else if (status == RequestStatus.CANCELLED) {
            events.add(AuditEvent.of(TYPE_STATUS, request.getUpdatedAt(),
                    request.getRequester().getUsername(), "Request cancelled", null));
        }

        // ── filters ───────────────────────────────────────────────────
        String typeFilter = type == null ? null : type.strip().toUpperCase();
        if (typeFilter != null && !isValidType(typeFilter)) {
            throw new IllegalArgumentException("Unknown audit event type: " + typeFilter
                    + " (expected one of STATUS, SUBMITTED, DECISION, JOB, SLA)");
        }
        return events.stream()
                .filter(e -> typeFilter == null || e.type().equals(typeFilter))
                .filter(e -> from == null || !e.at().isBefore(from))
                .filter(e -> to == null || !e.at().isAfter(to))
                .sorted(Comparator.comparing(AuditEvent::at).thenComparing(e -> e.type() == null ? "" : e.type()))
                .toList();
    }

    private static boolean isValidType(String t) {
        return t.equals(TYPE_STATUS) || t.equals(TYPE_SUBMITTED) || t.equals(TYPE_DECISION)
                || t.equals(TYPE_JOB) || t.equals(TYPE_SLA);
    }
}
