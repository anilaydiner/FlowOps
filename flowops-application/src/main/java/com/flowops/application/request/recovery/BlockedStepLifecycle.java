package com.flowops.application.request.recovery;

import com.flowops.application.audit.LifecycleAuditRecorder;
import com.flowops.application.notification.outbox.OutboxService;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.request.*;
import com.flowops.domain.request.port.RequestRepository;
import com.flowops.domain.request.port.StepInstanceRepository;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Called only by commands already holding the parent request write lock. */
@Service
public class BlockedStepLifecycle {

    private final java.time.Clock clock;

    private final RecoveryOperations operations;

    private final LifecycleAuditRecorder audit;

    private final OutboxService outbox;

    private final StepInstanceRepository steps;

    private final RequestRepository requests;

    public BlockedStepLifecycle(RecoveryOperations operations, LifecycleAuditRecorder audit, OutboxService outbox,
            StepInstanceRepository steps, RequestRepository requests, java.time.Clock clock) {
        this.clock = clock;
        this.operations = operations;
        this.audit = audit;
        this.outbox = outbox;
        this.steps = steps;
        this.requests = requests;
    }

    public void prepareBlock(Request request, StepInstance step, BlockReason reason) {
        var owner = operations.available(request, step);
        step.block(reason, reason.name(), owner.ownerId(), owner.team(), clock.instant());
    }

    public void blockPending(Request request, StepInstance step, BlockReason reason, CallerIdentity caller) {
        prepareBlock(request, step, reason);
        request.recordActivity();
        requests.save(request);
        steps.save(step);
        recordEntry(request, step, caller);
    }

    public void recordEntry(Request request, StepInstance step, CallerIdentity caller) {
        audit.record(request, caller, "STEP", "BLOCKED", key(step) + ":blocked",
                "Step " + step.getStepCode() + " blocked: " + step.getBlockReasonCode(), Map.of("state", "PENDING"),
                Map.of("state", "BLOCKED", "blockReasonCode", step.getBlockReasonCode().name()), ownerDetails(step),
                step.getBlockedReason());
        var recipients = new LinkedHashSet<>(operations.available(request, step).recipients());
        recipients.add(request.getRequester().getId());
        for (Long recipient : recipients) {
            String type = "BLOCKED:" + step.getBlockEpisode();
            String title = "Request #" + request.getId() + " needs workflow recovery";
            String body = "Step " + step.getStepName() + ": " + step.getBlockReasonCode() + ". Recovery team: "
                    + step.getRecoveryTeam();
            outbox.notifyStepFeed(request.getId(), step.getId(), recipient, "NOTIFY_" + type, title, body,
                    request.getCorrelationId());
            outbox.notifyStepEmail(request.getId(), step.getId(), recipient, type, title, body, null,
                    request.getCorrelationId());
        }
    }

    private static Map<String, Object> ownerDetails(StepInstance step) {
        Map<String, Object> details = new java.util.LinkedHashMap<>();
        details.put("stepInstanceId", step.getId());
        details.put("blockEpisode", step.getBlockEpisode());
        details.put("blockedAt", step.getBlockedAt().toString());
        if (step.getRecoveryOwnerId() != null)
            details.put("recoveryOwnerId", step.getRecoveryOwnerId());
        details.put("recoveryTeam", step.getRecoveryTeam());
        details.put("recoveryOwnerUnavailable", step.isRecoveryOwnerUnavailable());
        return details;
    }

    public static String key(StepInstance step) {
        return "step:" + step.getId() + ":block:" + step.getBlockEpisode();
    }

}
