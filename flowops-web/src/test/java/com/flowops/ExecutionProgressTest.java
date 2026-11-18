package com.flowops;

import com.flowops.application.request.model.ExecutionProgress;
import com.flowops.application.request.model.ExecutionProgress.*;
import com.flowops.domain.request.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionProgressTest {
    @Test void aClosedUnknownOutcomeIsNeverPresentedAsAwaitingCompletion(){
        var p=ExecutionProgress.from(new Facts(RequestStatus.CLOSED,null,false,false,true,true,0,0,0,0,0,JobCounts.empty()));
        assertEquals(State.UNKNOWN,p.state());assertEquals(List.of(AttentionReason.OUTCOME_UNKNOWN),p.attentionReasons());assertTrue(p.requiresAttention());
    }
    @Test void legacyExecutedOutcomeDoesNotClaimAKnownEmptyPlanOrDemandReplay(){
        var p=ExecutionProgress.from(new Facts(RequestStatus.CLOSED,RequestOutcome.EXECUTED,false,false,false,false,null,0,0,0,0,JobCounts.empty()));
        assertEquals(State.UNKNOWN,p.state());assertNull(p.expectedJobs());assertFalse(p.requiresAttention());
    }
    @Test void aManifestBeforeApprovalOrAfterRejectionIsAnIntegrityProblem(){
        for(var status:List.of(RequestStatus.DRAFT,RequestStatus.SUBMITTED,RequestStatus.IN_REVIEW,RequestStatus.REJECTED,RequestStatus.EXPIRED)){
            var p=ExecutionProgress.from(new Facts(status,null,false,false,true,true,0,0,0,0,0,JobCounts.empty()));
            assertEquals(State.RECONCILIATION_REQUIRED,p.state());assertTrue(p.attentionReasons().contains(AttentionReason.PLAN_INCONSISTENT));
        }
    }
}
