package com.flowops.application.request.recovery;

import com.flowops.application.audit.LifecycleAuditRecorder;
import com.flowops.application.notification.outbox.OutboxService;
import com.flowops.domain.request.*;
import com.flowops.domain.request.port.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ReassignRecoveryOwner {
    private final RecoveryOperations operations;
    private final RequestRepository requests;
    private final StepInstanceRepository steps;
    private final LifecycleAuditRecorder audit;
    private final OutboxService outbox;
    public ReassignRecoveryOwner(RecoveryOperations operations, RequestRepository requests, StepInstanceRepository steps,
                                 LifecycleAuditRecorder audit, OutboxService outbox) {
        this.operations=operations;this.requests=requests;this.steps=steps;this.audit=audit;this.outbox=outbox;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void reconcileLocked(Request request,StepInstance step) {
        if(request.getStatus()!=RequestStatus.IN_REVIEW || step.getState()!=StepState.BLOCKED) return;
        var available=operations.available(request,step);
        if(Objects.equals(step.getRecoveryTeam(),available.team()) && operations.eligibleOwner(request,step,step.getRecoveryOwnerId())) return;
        if(available.ownerId()==null && step.isRecoveryOwnerUnavailable() && Objects.equals(step.getRecoveryTeam(),available.team())) return;
        Map<String,Object> before=state(step);
        step.reassignRecoveryOwner(available.ownerId(),available.team());
        request.recordActivity(); requests.save(request); steps.save(step);
        String action=step.isRecoveryOwnerUnavailable()?"OWNER_UNAVAILABLE":"OWNER_REASSIGNED";
        audit.record(request,null,"STEP",action,BlockedStepLifecycle.key(step)+":owner:"+step.getRecoveryOwnerRevision(),
                step.isRecoveryOwnerUnavailable()?"No authorized recovery owner available":"Recovery responsibility reassigned",
                before,state(step),Map.of("stepInstanceId",step.getId(),"blockEpisode",step.getBlockEpisode(),
                        "ownerRevision",step.getRecoveryOwnerRevision()),"Current recovery authority changed");
        var recipients=new LinkedHashSet<>(available.recipients());
        recipients.add(request.getRequester().getId()); // Do not notify a revoked former owner.
        String suffix=":episode:"+step.getBlockEpisode()+":owner:"+step.getRecoveryOwnerRevision();
        String title=step.isRecoveryOwnerUnavailable()?"Workflow recovery needs an operator":"Workflow recovery owner changed";
        String body="Request #"+request.getId()+", step "+step.getStepName()+", recovery team "+step.getRecoveryTeam();
        for(Long recipient:recipients) {
            outbox.notifyStepFeed(request.getId(),step.getId(),recipient,"RECOVERY_OWNER_FEED",title,body,request.getCorrelationId(),suffix);
            outbox.notifyStepEmail(request.getId(),step.getId(),recipient,"RECOVERY_OWNER",title,body,null,request.getCorrelationId(),suffix);
        }
    }
    private static Map<String,Object> state(StepInstance step) {
        Map<String,Object> state=new LinkedHashMap<>();
        if(step.getRecoveryOwnerId()!=null) state.put("ownerId",step.getRecoveryOwnerId());
        state.put("team",step.getRecoveryTeam());
        state.put("unavailable",step.isRecoveryOwnerUnavailable());return state;
    }
}
