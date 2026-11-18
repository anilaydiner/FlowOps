package com.flowops.application.request.model;

import com.flowops.domain.request.*;
import java.util.*;

/** Live read projection. An unknown expected set is never represented as an empty plan. */
public record ExecutionProgress(State state,Integer expectedJobs,JobCounts jobs,List<AttentionReason> attentionReasons) {
    public enum State { NOT_PLANNED, UNKNOWN, QUEUED, RUNNING, RETRYING, COMPLETION_PENDING, COMPLETED, STOPPED, RECONCILIATION_REQUIRED }
    public enum AttentionReason {
        REVIEW_BLOCKED, OUTCOME_UNKNOWN, EXECUTION_HISTORY_UNKNOWN, MISSING_FROZEN_PLAN,
        PLAN_INCONSISTENT, MISSING_JOBS, MISSING_JOB_BINDINGS, JOB_IDENTITY_MISMATCH,
        MATERIALIZATION_UNVERIFIABLE, UNEXPECTED_JOBS, UNCERTAIN_EXECUTION, RETRIES_EXHAUSTED,
        EXECUTION_LEASE_EXPIRED, EXPECTED_JOB_CANCELLED, STOPPED_REQUEST_HAS_RUNNABLE_JOBS
    }
    public record JobCounts(int total,int planned,int running,int retrying,int completed,int uncertain,int deadLetter,
                            int cancelled,int missing,int missingBindings,int invalidBindings,int unexpected,int expiredLeases) {
        public static JobCounts empty(){return new JobCounts(0,0,0,0,0,0,0,0,0,0,0,0,0);}
    }
    /** Facts from one batched scalar query; no provider lookup or status mutation is involved. */
    public record Facts(RequestStatus status,RequestOutcome outcome,boolean startUnknown,boolean blocked,
                        boolean manifestPresent,boolean headerMatches,Integer expectedJobs,int actions,int matchedJobs,
                        int completedExpectedJobs,int receiptIssues,JobCounts jobs) {}
    public ExecutionProgress {attentionReasons=List.copyOf(attentionReasons);}
    public boolean requiresAttention(){return !attentionReasons.isEmpty();}

    public static ExecutionProgress from(Facts f){
        var reasons=new LinkedHashSet<AttentionReason>();var j=f.jobs();
        if(f.blocked())reasons.add(AttentionReason.REVIEW_BLOCKED);
        if(f.status()==RequestStatus.CLOSED && f.outcome()==null)reasons.add(AttentionReason.OUTCOME_UNKNOWN);
        if(f.status()==RequestStatus.APPROVED && f.startUnknown())reasons.add(AttentionReason.EXECUTION_HISTORY_UNKNOWN);
        boolean executed=f.status()==RequestStatus.EXECUTED || f.outcome()==RequestOutcome.EXECUTED;
        boolean stopped=f.status()==RequestStatus.CANCELLED || f.status()==RequestStatus.EXPIRED || f.status()==RequestStatus.REJECTED || f.outcome()==RequestOutcome.REJECTED;
        boolean exact=f.manifestPresent() && f.headerMatches() && Objects.equals(f.expectedJobs(),f.actions())
                && Objects.equals(f.expectedJobs(),f.matchedJobs()) && Objects.equals(f.expectedJobs(),j.total());
        if(!f.manifestPresent()){
            if(f.status()==RequestStatus.APPROVED || (!f.status().isFinalized() && j.total()>0))reasons.add(AttentionReason.MISSING_FROZEN_PLAN);
        }else{
            if(f.status()==RequestStatus.DRAFT || f.status()==RequestStatus.SUBMITTED || f.status()==RequestStatus.IN_REVIEW
                    || f.status()==RequestStatus.EXPIRED || f.status()==RequestStatus.REJECTED || f.outcome()==RequestOutcome.REJECTED)reasons.add(AttentionReason.PLAN_INCONSISTENT);
            if(!f.headerMatches() || !Objects.equals(f.expectedJobs(),f.actions()))reasons.add(AttentionReason.PLAN_INCONSISTENT);
            if(j.missing()>0)reasons.add(AttentionReason.MISSING_JOBS);
            if(j.missingBindings()>0)reasons.add(AttentionReason.MISSING_JOB_BINDINGS);
            if(j.invalidBindings()>0)reasons.add(AttentionReason.JOB_IDENTITY_MISMATCH);
            if(f.receiptIssues()>0)reasons.add(AttentionReason.MATERIALIZATION_UNVERIFIABLE);
            if(!exact && reasons.isEmpty())reasons.add(AttentionReason.PLAN_INCONSISTENT);
        }
        if(j.unexpected()>0)reasons.add(AttentionReason.UNEXPECTED_JOBS);
        if(j.uncertain()>0)reasons.add(AttentionReason.UNCERTAIN_EXECUTION);
        if(j.deadLetter()>0)reasons.add(AttentionReason.RETRIES_EXHAUSTED);
        if(j.expiredLeases()>0)reasons.add(AttentionReason.EXECUTION_LEASE_EXPIRED);
        if(f.status()==RequestStatus.APPROVED && j.cancelled()>0)reasons.add(AttentionReason.EXPECTED_JOB_CANCELLED);
        if(stopped && j.planned()+j.running()+j.retrying()>0)reasons.add(AttentionReason.STOPPED_REQUEST_HAS_RUNNABLE_JOBS);
        if(executed && f.manifestPresent() && (!exact || !Objects.equals(f.expectedJobs(),f.completedExpectedJobs())))reasons.add(AttentionReason.PLAN_INCONSISTENT);
        State state;
        boolean executionProblem=reasons.stream().anyMatch(r->r!=AttentionReason.REVIEW_BLOCKED && r!=AttentionReason.OUTCOME_UNKNOWN);
        if(executionProblem)state=State.RECONCILIATION_REQUIRED;
        else if(f.status()==RequestStatus.CLOSED && f.outcome()==null)state=State.UNKNOWN;
        else if(stopped)state=State.STOPPED;
        else if(!f.manifestPresent())state=executed || f.status()==RequestStatus.CLOSED || f.status()==RequestStatus.APPROVED?State.UNKNOWN:State.NOT_PLANNED;
        else if(exact && Objects.equals(f.expectedJobs(),f.completedExpectedJobs()))state=executed?State.COMPLETED:State.COMPLETION_PENDING;
        else if(j.running()>0)state=State.RUNNING;
        else if(j.retrying()>0)state=State.RETRYING;
        else state=State.QUEUED;
        return new ExecutionProgress(state,f.manifestPresent()?f.expectedJobs():null,j,new ArrayList<>(reasons));
    }
    public static ExecutionProgress unplanned(RequestStatus status,RequestOutcome outcome){
        return from(new Facts(status,outcome,false,false,false,false,null,0,0,0,0,JobCounts.empty()));
    }
}
