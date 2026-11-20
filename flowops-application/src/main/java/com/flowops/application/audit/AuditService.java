package com.flowops.application.audit;

import com.flowops.application.notification.outbox.OutboxMessage;
import com.flowops.application.audit.port.LifecycleAuditRepository;
import com.flowops.application.audit.model.LifecycleAuditRecord;
import com.flowops.application.notification.outbox.port.OutboxMessageRepository;
import com.flowops.domain.audit.AuditEvent;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.exception.RequestValidationException;
import com.flowops.domain.execution.ExecutionJob;
import com.flowops.domain.execution.port.ExecutionJobRepository;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.policy.AuthorizationEvaluator;
import com.flowops.domain.identity.policy.PermissionCodes;
import com.flowops.domain.request.Decision;
import com.flowops.domain.request.Request;
import com.flowops.domain.request.RequestStatus;
import com.flowops.domain.request.StepInstance;
import com.flowops.domain.request.port.DecisionRepository;
import com.flowops.domain.request.port.RequestRepository;
import com.flowops.domain.request.port.StepInstanceRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stored lifecycle facts are authoritative for requests with audit coverage. Untracked
 * database imports have an explicitly labeled reconstructed fallback. Participant-based
 * request read access never grants the independent audit permissions required here.
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

    private final LifecycleAuditRepository recorded;

    public AuditService(RequestRepository requests, StepInstanceRepository stepInstances, DecisionRepository decisions,
            ExecutionJobRepository executionJobs, OutboxMessageRepository outbox, AuthorizationEvaluator authz,
            LifecycleAuditRepository recorded) {
        this.requests = requests;
        this.stepInstances = stepInstances;
        this.decisions = decisions;
        this.executionJobs = executionJobs;
        this.outbox = outbox;
        this.authz = authz;
        this.recorded = recorded;
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> timeline(Long requestId, Instant from, Instant to, String type, CallerIdentity caller) {
        Request request = requests.findById(requestId)
            .orElseThrow(() -> new NotFoundException("Request not found: " + requestId));
        boolean isOwn = caller.id().equals(request.getRequester().getId());
        if (isOwn) {
            authz.require(caller, PermissionCodes.AUDIT_READ);
        }
        else {
            authz.require(caller, PermissionCodes.AUDIT_READ_ANY);
        }

        String normalized = type == null ? null : type.strip().toUpperCase(java.util.Locale.ROOT);
        if (normalized != null && !isValidType(normalized)) {
            throw new RequestValidationException(Map.of("type", "Unknown audit event type: " + normalized));
        }
        if (recorded.hasCoverage(requestId)) {
            return recorded.findByRequestId(requestId)
                .stream()
                .map(AuditService::toView)
                .filter(e -> normalized == null || normalized.equals(e.type()))
                .filter(e -> from == null || !e.at().isBefore(from))
                .filter(e -> to == null || !e.at().isAfter(to))
                .toList();
        }
        // Only untracked database imports use this explicitly reconstructed fallback.
        List<AuditEvent> events = new ArrayList<>();

        // ── STATUS: creation ──────────────────────────────────────────
        events.add(AuditEvent.of(TYPE_STATUS, request.getCreatedAt(), null, "Legacy request creation record",
                Map.of("type", request.getType().getCode())));

        // ── SUBMITTED ─────────────────────────────────────────────────
        if (request.getSubmittedAt() != null) {
            events.add(AuditEvent.of(TYPE_SUBMITTED, request.getSubmittedAt(), null,
                    "Legacy submission record; original submitter unavailable", null));
        }

        // ── DECISION ──────────────────────────────────────────────────
        for (StepInstance si : stepInstances.findByRequestIdOrderById(requestId)) {
            for (Decision d : decisions.findByStepInstanceIdOrderById(si.getId())) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("step", si.getStepCode());
                details.put("action", d.getAction().name());
                if (d.getJustification() != null)
                    details.put("justification", d.getJustification());
                if (d.getDelegatedTo() != null)
                    details.put("delegatedTo", d.getDelegatedTo());
                details.put("evidence", d.getEvidence());
                events.add(AuditEvent.of(TYPE_DECISION, d.getCreatedAt(), d.getActorUsername(),
                        d.getAction().name() + " on step " + si.getStepCode(), details));
            }
        }

        // ── JOB (created + finished) ──────────────────────────────────
        for (ExecutionJob job : executionJobs.findByRequestIdOrderByCreatedAtAsc(requestId)) {
            events.add(AuditEvent.of(TYPE_JOB, job.getCreatedAt(), null,
                    "Execution job " + job.getAction() + " enqueued", Map.of("action", job.getAction())));
            if (job.getCompletedAt() != null) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("action", job.getAction());
                details.put("status", job.getStatus().name());
                details.put("retryCount", job.getRetryCount());
                if (job.getResult() != null)
                    details.put("result", job.getResult());
                if (job.getLastError() != null)
                    details.put("lastError", job.getLastError());
                events.add(AuditEvent.of(TYPE_JOB, job.getCompletedAt(), null,
                        "Execution job " + job.getAction() + " finished: " + job.getStatus(), details));
            }
        }

        // ── SLA breach escalations (outbox rows for this request) ─────
        for (OutboxMessage msg : outbox.findByKeyPrefix(OutboxMessage.KEY_PREFIX_REQUEST + requestId + ":sla")) {
            events.add(AuditEvent.of(TYPE_SLA, msg.getCreatedAt(), null,
                    "SLA breach escalation to user " + msg.getRecipientId() + " (" + msg.getStatus() + ")",
                    Map.of("channel", msg.getChannel(), "outboxStatus", msg.getStatus().name(), "recipientId",
                            msg.getRecipientId())));
        }

        // ── STATUS: reached state (if past review) ────────────────────
        RequestStatus status = request.getStatus();
        if (status == RequestStatus.APPROVED || status == RequestStatus.EXECUTED || status == RequestStatus.CLOSED
                || status == RequestStatus.REJECTED || status == RequestStatus.EXPIRED) {
            events.add(AuditEvent.of(TYPE_STATUS, request.getUpdatedAt(), null, "Request is " + status, null));
        }
        else if (status == RequestStatus.CANCELLED) {
            events
                .add(AuditEvent.of(TYPE_STATUS, request.getUpdatedAt(), null, "Legacy cancellation observation", null));
        }

        // ── filters ───────────────────────────────────────────────────
        String typeFilter = type == null ? null : type.strip().toUpperCase();
        if (typeFilter != null && !isValidType(typeFilter)) {
            throw new RequestValidationException(Map.of("type", "Unknown audit event type: " + typeFilter
                    + " (expected one of STATUS, SUBMITTED, DECISION, JOB, SLA)"));
        }
        return events.stream()
            .filter(e -> typeFilter == null || e.type().equals(typeFilter))
            .filter(e -> from == null || !e.at().isBefore(from))
            .filter(e -> to == null || !e.at().isAfter(to))
            .sorted(Comparator.comparing(AuditEvent::at).thenComparing(e -> e.type() == null ? "" : e.type()))
            .toList();
    }

    private static AuditEvent toView(LifecycleAuditRecord record) {
        Map<String, Object> details = new LinkedHashMap<>(record.details());
        details.putIfAbsent("action", record.action());
        details.put("eventAction", record.action());
        details.put("before", record.before());
        details.put("after", record.after());
        details.put("actorKind", record.actorKind());
        if (record.actorId() != null)
            details.put("actorId", record.actorId());
        if (record.correlationId() != null)
            details.put("correlationId", record.correlationId());
        if (record.remoteAddress() != null)
            details.put("remoteAddress", record.remoteAddress());
        if (record.userAgent() != null)
            details.put("userAgent", record.userAgent());
        if (record.reason() != null)
            details.put("reason", record.reason());
        return new AuditEvent(record.type(), record.at(), record.actor(), record.summary(), details, record.id(),
                record.sequence(), record.provenance());
    }

    private static boolean isValidType(String t) {
        return t.equals(TYPE_STATUS) || t.equals(TYPE_SUBMITTED) || t.equals(TYPE_DECISION) || t.equals(TYPE_JOB)
                || t.equals(TYPE_SLA) || t.equals("REQUEST") || t.equals("STEP") || t.equals("PARTICIPANT")
                || t.equals("CLARIFICATION") || t.equals("APPROVAL") || t.equals("EXECUTION")
                || t.equals("EXECUTION_PLAN");
    }

}
