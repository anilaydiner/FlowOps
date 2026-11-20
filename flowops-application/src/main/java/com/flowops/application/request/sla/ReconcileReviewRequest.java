package com.flowops.application.request.sla;

import com.flowops.application.audit.LifecycleAuditRecorder;
import com.flowops.application.notification.outbox.OutboxService;
import com.flowops.application.request.port.RequestWriteLock;
import com.flowops.application.request.port.SlaMetrics;
import com.flowops.application.request.recovery.RecoverBlockedRequest;
import com.flowops.application.request.recovery.RecoveryOperations;
import com.flowops.domain.identity.port.UserRepository;
import com.flowops.domain.request.*;
import com.flowops.domain.request.port.*;
import java.time.Clock;
import java.util.LinkedHashSet;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

/** One parent-locked transaction, shared by both background scanners. */
@Service
public class ReconcileReviewRequest {
    private final RequestRepository requests;
    private final RequestWriteLock requestWriteLock;
    private final StepInstanceRepository steps;
    private final RecoverBlockedRequest recovery;
    private final RecoveryOperations operations;
    private final UserRepository users;
    private final LifecycleAuditRecorder audit;
    private final OutboxService outbox;
    private final SlaMetrics metrics;
    private final Clock clock;
    private final com.flowops.application.request.port.EligibilityReconciliationQueue queue;
    private final com.flowops.application.request.recovery.ReassignRecoveryOwner ownership;

    public ReconcileReviewRequest(RequestRepository requests, RequestWriteLock requestWriteLock, StepInstanceRepository steps, RecoverBlockedRequest recovery,
                                  RecoveryOperations operations, UserRepository users, LifecycleAuditRecorder audit,
                                  OutboxService outbox, SlaMetrics metrics, Clock clock,
                                  com.flowops.application.request.port.EligibilityReconciliationQueue queue,
                                  com.flowops.application.request.recovery.ReassignRecoveryOwner ownership) {
        this.queue=queue; this.ownership=ownership;
        this.requests=requests;
        this.requestWriteLock = requestWriteLock;
        this.steps=steps; this.recovery=recovery; this.operations=operations;
        this.users=users; this.audit=audit; this.outbox=outbox; this.metrics=metrics; this.clock=clock;
    }

    public record Result(int breaches, int expiries, boolean processed) {}

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public Result process(Long id, boolean checkEligibility) {
        var request=requestWriteLock.tryFindByIdForUpdate(id).orElse(null);
        return processLocked(request,checkEligibility);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public boolean processQueued(com.flowops.application.request.port.EligibilityReconciliationQueue.Ticket ticket) {
        var request=requestWriteLock.tryFindByIdForUpdate(ticket.requestId()).orElse(null);
        if(request==null) {
            if(requests.findById(ticket.requestId()).isPresent()) return false;
        } else processLocked(request,true);
        queue.acknowledge(ticket);
        return true;
    }

    private Result processLocked(Request request, boolean checkEligibility) {
        if(request==null || request.getStatus()!=RequestStatus.IN_REVIEW) return new Result(0,0,false);
        Long id=request.getId();
        var step=steps.findActiveForLockedRequest(id).orElse(null);
        if(step==null) return new Result(0,0,false);
        var now=clock.instant(); // Evaluate after acquiring the lock, never using discovery-time state.
        if(step.isExpired(now)) {
            var before=LifecycleAuditRecorder.state(request);
            String previous=step.getState().name();
            request.expire(now);
            requests.save(request);
            step.cancel(); steps.save(step);
            audit.record(request,null,"STEP","CANCELLED","step:"+step.getId()+":cancelled",
                    "Active step stopped on business expiry",Map.of("state",previous),Map.of("state","CANCELLED"),
                    Map.of("stepInstanceId",step.getId(),"expiresAt",step.getExpiresAt().toString()),"Business review deadline elapsed");
            audit.transition(request,null,before,"Business review deadline elapsed");
            outbox.notifyRequesterExpiry(request.getId(),request.getRequester().getId(),request.getCorrelationId());
            afterCommit(()->metrics.recordExpiry(step.getStepCode()));
            return new Result(0,1,true);
        }
        if(checkEligibility) recovery.reconcileLocked(request,step,"Eligibility reconciliation",null);
        if(!checkEligibility && step.getState()==StepState.BLOCKED) ownership.reconcileLocked(request,step);
        if(!step.escalationDue(now)) return new Result(0,0,true);
        int level=step.getEscalationLevel()+1;
        var recipients=new LinkedHashSet<Long>();
        users.findActiveByRoleCode(step.escalationRole()).stream().map(u->u.getId()).forEach(recipients::add);
        recipients.remove(request.getRequester().getId());
        if(step.getState()==StepState.BLOCKED || level>=2 || recipients.isEmpty()) recipients.addAll(operations.available(request,step).recipients());
        recipients.remove(request.getRequester().getId());
        String suffix=":episode:"+step.getBlockEpisode()+":level:"+level;
        boolean overdue=step.isSlaBreached(now);
        Map<String,Object> details=new java.util.LinkedHashMap<>();
        details.put("stepInstanceId",step.getId()); details.put("step",step.getStepCode());
        details.put("blockEpisode",step.getBlockEpisode()); details.put("level",level);
        details.put("scheduledAt",step.getNextEscalationAt().toString());
        details.put("cause",overdue?"REVIEW_OVERDUE":"BLOCKED");
        if(step.getReviewDueAt()!=null) details.put("deadline",step.getReviewDueAt().toString());
        if(step.getBlockedAt()!=null) details.put("blockedAt",step.getBlockedAt().toString());
        if(recipients.isEmpty()) {
            step.deferEscalation(now); steps.save(step);
            request.recordActivity();requests.save(request);
            details.put("retryAt",step.getNextEscalationAt().toString());
            audit.record(request,null,"SLA","RECIPIENTS_UNAVAILABLE","step:"+step.getId()+":sla"+suffix+":unavailable:"+details.get("scheduledAt"),
                    "Escalation has no available recipients",Map.of(),Map.of("delivered",false),details,null);
            return new Result(0,0,true);
        }
        String title="Request #"+id+" escalation level "+level;
        String body="Step "+step.getStepName()+" is "+(overdue?"past its review SLA":"blocked")
                +". State: "+step.getState()+". Escalation level: "+level;
        for(Long recipient:recipients) {
            outbox.notifyStepFeed(id,step.getId(),recipient,"SLA_BREACH_FEED",title,body,request.getCorrelationId(),suffix);
            outbox.notifyStepEmail(id,step.getId(),recipient,"SLA_BREACH",title,body,null,request.getCorrelationId(),suffix);
        }
        step.recordEscalation(now); steps.save(step);
        if(step.getNextEscalationAt()!=null) details.put("nextEscalationAt",step.getNextEscalationAt().toString());
        details.put("recipientCount",recipients.size());
        audit.record(request,null,"SLA",overdue?"BREACHED":"ESCALATED","step:"+step.getId()+":sla"+suffix,
                overdue?"SLA breach on review step, escalation level "+level:"Blocked review escalation level "+level,
                Map.of("escalationLevel",level-1),Map.of("escalationLevel",level),details,null);
        request.recordActivity(); requests.save(request);
        afterCommit(()->recipients.forEach(recipient->metrics.recordBreach(step.getStepCode())));
        return new Result(recipients.size(),0,true);
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }
}
