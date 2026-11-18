package com.flowops.application.audit;

import com.flowops.application.audit.model.LifecycleAuditRecord;
import com.flowops.application.audit.port.LifecycleAuditRepository;
import com.flowops.domain.CorrelationId;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.request.Request;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Every fact joins the business transaction; a failed fact write must fail that mutation. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class LifecycleAuditRecorder {
    private final LifecycleAuditRepository repository;
    private final Clock clock;

    public LifecycleAuditRecorder(LifecycleAuditRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /** Deliberately omits request payload values and external results/credentials. */
    public static Map<String, Object> state(Request request) {
        var state=new java.util.LinkedHashMap<String,Object>();
        state.put("status",request.getStatus().name());state.put("version",request.getVersion());
        state.put("typeCode",request.getType().getCode());state.put("requesterId",request.getRequester().getId());
        if(request.getOutcome()!=null)state.put("outcome",request.getOutcome().name());
        if(request.getApprovedAt()!=null)state.put("approvedAt",request.getApprovedAt().toString());
        if(request.getRejectedAt()!=null)state.put("rejectedAt",request.getRejectedAt().toString());
        if(request.getExecutedAt()!=null)state.put("executedAt",request.getExecutedAt().toString());
        if(request.getCancelledAt()!=null)state.put("cancelledAt",request.getCancelledAt().toString());
        if(request.getExpiredAt()!=null)state.put("expiredAt",request.getExpiredAt().toString());
        if(request.getClosedAt()!=null)state.put("closedAt",request.getClosedAt().toString());
        return Map.copyOf(state);
    }

    public void created(Request request, CallerIdentity caller) {
        repository.beginCoverage(request.getId(), clock.instant(), false);
        record(request, caller, "STATUS", "CREATED", "created", "Request created as DRAFT",
                Map.of(), state(request), Map.of(), null);
    }

    public void transition(Request request, CallerIdentity caller, Map<String, Object> before, String reason) {
        if (before.get("status").equals(request.getStatus().name())) return;
        record(request, caller, request.getStatus() == com.flowops.domain.request.RequestStatus.SUBMITTED ? "SUBMITTED" : "STATUS",
                "STATUS_CHANGED", "status:" + request.getVersion() + ":" + before.get("status") + ":" + request.getStatus(),
                "Request is " + request.getStatus(), before, state(request), Map.of(), reason);
    }

    public void record(Request request, CallerIdentity caller, String type, String action, String suffix,
                       String summary, Map<String, Object> before, Map<String, Object> after,
                       Map<String, Object> details, String reason) {
        var now = clock.instant();
        if (repository.beginCoverage(request.getId(), now, true)) {
            repository.appendIfAbsent(new LifecycleAuditRecord(UUID.randomUUID(), null, "request:" + request.getId() + ":baseline",
                    request.getId(), "STATUS", "LEGACY_BASELINE", now, null, "system", "SYSTEM", null, null, null,
                    null, "Audit capture started; earlier transitions are unavailable", Map.of(),
                    before.isEmpty() ? state(request) : before, Map.of(), "LEGACY_BASELINE"));
        }
        AuditRequestOrigin origin = caller == null ? null : AuditRequestOrigin.current();
        String correlationId = origin == null ? MDC.get(CorrelationId.MDC_KEY) : origin.correlationId();
        if (correlationId == null) correlationId = request.getCorrelationId();
        repository.appendIfAbsent(new LifecycleAuditRecord(UUID.randomUUID(), null, "request:" + request.getId() + ":" + suffix,
                request.getId(), type, action, now, caller == null ? null : caller.id(),
                caller == null ? "system" : caller.username(), caller == null ? "SYSTEM" : "HUMAN", correlationId,
                origin == null ? null : origin.remoteAddress(), origin == null ? null : origin.userAgent(), reason,
                summary, before, after, details, "RECORDED"));
    }
}
