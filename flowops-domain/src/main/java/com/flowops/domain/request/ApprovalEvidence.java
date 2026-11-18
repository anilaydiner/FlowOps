package com.flowops.domain.request;

import com.flowops.domain.exception.ConflictException;
import java.util.*;

/** An automatic workflow outcome never invents a human approver. */
public record ApprovalEvidence(Trigger trigger,ActorKind actorKind,Long actorId,String actorName,
                               Long initiatedById,String initiatedByName,Long decisionId,Long stepInstanceId) {
    public enum Trigger { FINAL_HUMAN_APPROVAL, ALL_STEPS_SKIPPED, LEGACY_PLAN_DISPOSITION }
    public enum ActorKind { HUMAN, SYSTEM, UNKNOWN }
    public ApprovalEvidence {
        Objects.requireNonNull(trigger);Objects.requireNonNull(actorKind);
        if(actorName==null || actorName.isBlank()) throw new IllegalArgumentException("Approval actor name is required");
        if(trigger==Trigger.FINAL_HUMAN_APPROVAL && (actorKind!=ActorKind.HUMAN || actorId==null || decisionId==null || stepInstanceId==null))
            throw new IllegalArgumentException("Final human approval requires actor, decision and step evidence");
        if(trigger==Trigger.ALL_STEPS_SKIPPED && (actorKind!=ActorKind.SYSTEM || actorId!=null || decisionId!=null || stepInstanceId!=null))
            throw new IllegalArgumentException("All-skipped approval is a system outcome without a vote");
        if(trigger==Trigger.LEGACY_PLAN_DISPOSITION && (actorKind!=ActorKind.UNKNOWN || actorId!=null || decisionId!=null || stepInstanceId!=null || initiatedById==null || initiatedByName==null))
            throw new IllegalArgumentException("Legacy planning retains unknown original approval attribution and identifies its recovery operator");
    }
    public static ApprovalEvidence human(Long actorId,String name,Long decisionId,Long stepId) {
        return new ApprovalEvidence(Trigger.FINAL_HUMAN_APPROVAL,ActorKind.HUMAN,actorId,name,actorId,name,decisionId,stepId);
    }
    public static ApprovalEvidence allSkipped(Long initiatorId,String name) {
        return new ApprovalEvidence(Trigger.ALL_STEPS_SKIPPED,ActorKind.SYSTEM,null,"system",initiatorId,name,null,null);
    }
    public static ApprovalEvidence legacyPlan(Long operatorId,String name){
        return new ApprovalEvidence(Trigger.LEGACY_PLAN_DISPOSITION,ActorKind.UNKNOWN,null,"unknown",operatorId,name,null,null);
    }
    public record FrozenPlan(UUID id, Long requestId, java.time.Instant plannedAt, ApprovalEvidence evidence) {
        public FrozenPlan {
            Objects.requireNonNull(id);
            Objects.requireNonNull(requestId);
            Objects.requireNonNull(plannedAt);
            Objects.requireNonNull(evidence);
        }
    }

    void verifyReview(Request request, List<StepInstance> stepsInActivationOrder, List<Decision> votes) {
        if (trigger == Trigger.LEGACY_PLAN_DISPOSITION) {
            throw new ConflictException("legacy-plan-state", "Historical planning uses the reviewed disposition operation, not approval finalization");
        }
        if (request.getStatus() != RequestStatus.SUBMITTED && request.getStatus() != RequestStatus.IN_REVIEW) {
            throw new ConflictException("execution-plan-unknown", "Only a new completed review may freeze an execution plan");
        }
        if (stepsInActivationOrder.stream().anyMatch(step -> step.getRequest() == null
                || !Objects.equals(step.getRequest().getId(), request.getId())
                || (step.getState() != StepState.SKIPPED && step.getState() != StepState.SATISFIED))) {
            throw new ConflictException("Approval still has unsatisfied workflow steps");
        }
        if (trigger == Trigger.ALL_STEPS_SKIPPED) {
            if (request.getStatus() != RequestStatus.SUBMITTED
                    || stepsInActivationOrder.stream().anyMatch(step -> step.getState() != StepState.SKIPPED)) {
                throw new ConflictException("Automatic approval requires an all-skipped submission");
            }
            return;
        }
        var last = stepsInActivationOrder.reversed().stream()
                .filter(step -> step.getState() == StepState.SATISFIED && step.getId() != null)
                .findFirst()
                .orElseThrow(() -> new ConflictException("Approval evidence does not identify a satisfied step"));
        if (request.getStatus() != RequestStatus.IN_REVIEW || !Objects.equals(last.getId(), stepInstanceId)
                || !Objects.equals(last.getSatisfiedBy(), actorId)) {
            throw new ConflictException("Approval evidence must identify the final completing human vote");
        }
        boolean recorded = votes.stream().anyMatch(vote -> decisionId.equals(vote.getId())
                && vote.getAction() == DecisionAction.APPROVE && actorId.equals(vote.getActorId())
                && actorName.equals(vote.getActorUsername()) && vote.getStepInstance() != null
                && stepInstanceId.equals(vote.getStepInstance().getId()) && vote.getRequest() != null
                && Objects.equals(request.getId(), vote.getRequest().getId()));
        if (!recorded) throw new ConflictException("Approval evidence does not identify its recorded human vote");
    }

    public Map<String,Object> snapshot() {
        var m=new LinkedHashMap<String,Object>();m.put("trigger",trigger.name());m.put("actorKind",actorKind.name());
        m.put("actorId",actorId);m.put("actorName",actorName);m.put("initiatedById",initiatedById);m.put("initiatedByName",initiatedByName);
        m.put("decisionId",decisionId);m.put("stepInstanceId",stepInstanceId);return Collections.unmodifiableMap(m);
    }
    public static ApprovalEvidence fromSnapshot(Map<String,Object> m) {
        return new ApprovalEvidence(Trigger.valueOf((String)m.get("trigger")),ActorKind.valueOf((String)m.get("actorKind")),
                number(m,"actorId"),(String)m.get("actorName"),number(m,"initiatedById"),(String)m.get("initiatedByName"),number(m,"decisionId"),number(m,"stepInstanceId"));
    }
    private static Long number(Map<String,Object> m,String key) {return m.get(key)==null?null:((Number)m.get(key)).longValue();}
}
