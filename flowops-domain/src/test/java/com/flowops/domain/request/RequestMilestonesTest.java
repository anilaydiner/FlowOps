package com.flowops.domain.request;

import com.flowops.domain.execution.ExecutionPlan;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RequestMilestonesTest {

    private final Instant submitted = Instant.parse("2026-11-17T09:00:00Z"), approved = submitted.plusSeconds(60),
            executed = approved.plusSeconds(120), closed = executed.plusSeconds(3600);

    private Request review() {
        var r = Request.newDraft(null, null, Map.of(), null);
        r.markSubmitted(submitted);
        r.beginReview(submitted);
        return r;
    }

    private Request approvedRequest() {
        var r = Request.restore(new Request.Snapshot(1L, null, null, RequestStatus.SUBMITTED, Map.of(), 0L, null,
                submitted, submitted, submitted));
        var plan = new ExecutionPlan(UUID.randomUUID(), r.getId(), 1L, "TYPE", submitted, approved,
                ApprovalEvidence.allSkipped(1L, "requester"), List.of());
        r.approve(plan.approvalPlan(), List.of(), List.of());
        return r;
    }

    @Test
    void closurePreservesExecutedOutcomeAndEveryOriginalMilestone() {
        var r = approvedRequest();
        r.markExecutionStarted(approved.plusSeconds(1));
        r.completeExecution(new Request.ExecutionCompletion(r.getExecutionPlanId(), r.getId()), executed);
        r.close(closed);
        assertEquals(RequestStatus.CLOSED, r.getStatus());
        assertEquals(RequestOutcome.EXECUTED, r.getOutcome());
        assertEquals(submitted, r.getSubmittedAt());
        assertEquals(approved, r.getApprovedAt());
        assertEquals(executed, r.getExecutedAt());
        assertEquals(closed, r.getClosedAt());
        assertEquals(approved, r.getExecutionPlannedAt());
        assertEquals(approved.plusSeconds(1), r.getExecutionStartedAt());
        assertNull(r.getRejectedAt());
        assertThrows(RuntimeException.class, () -> r.beginReview(closed.plusSeconds(1)));
    }

    @Test
    void rejectedClosureDoesNotInventApprovalOrExecution() {
        var r = review();
        r.reject(approved);
        r.close(closed);
        assertEquals(RequestOutcome.REJECTED, r.getOutcome());
        assertEquals(approved, r.getRejectedAt());
        assertEquals(closed, r.getClosedAt());
        assertNull(r.getApprovedAt());
        assertNull(r.getExecutedAt());
        assertNull(r.getExecutionStartedAt());
    }

    @Test
    void cancellationAndExpiryAreDistinctOutcomesAndRepeatedCancellationPreservesItsTime() {
        var r = approvedRequest();
        r.cancel(executed);
        r.cancel(closed);
        assertEquals(RequestOutcome.CANCELLED, r.getOutcome());
        assertEquals(executed, r.getCancelledAt());
        assertEquals(approved, r.getApprovedAt());
        assertNull(r.getExecutedAt());
        var expired = review();
        expired.expire(executed);
        assertEquals(RequestOutcome.EXPIRED, expired.getOutcome());
        assertEquals(executed, expired.getExpiredAt());
    }

    @Test
    void closingAnObservedLegacyOutcomeDoesNotManufactureItsOriginalTime() {
        var r = Request.restore(new Request.Snapshot(1L, null, null, RequestStatus.EXECUTED, Map.of(), 0L, null,
                submitted, submitted, submitted));
        r.close(closed);
        assertEquals(RequestOutcome.EXECUTED, r.getOutcome());
        assertNull(r.getExecutedAt());
        assertNull(r.getApprovedAt());
        assertEquals(closed, r.getClosedAt());
    }

    @Test
    void lifecyclePredicatesDistinguishAReviewOutcomeFromFinalAdministrativeClosure() {
        for (var status : RequestStatus.values()) {
            assertEquals(status == RequestStatus.IN_REVIEW, status.acceptsReviewDecisions());
            assertEquals(Set.of(RequestStatus.REJECTED, RequestStatus.EXECUTED).contains(status), status.canClose());
            assertEquals(Set.of(RequestStatus.CLOSED, RequestStatus.CANCELLED, RequestStatus.EXPIRED).contains(status),
                    status.isTerminal());
            assertEquals(!Set
                .of(RequestStatus.DRAFT, RequestStatus.SUBMITTED, RequestStatus.IN_REVIEW, RequestStatus.APPROVED)
                .contains(status), status.isFinalized());
        }
    }

}
