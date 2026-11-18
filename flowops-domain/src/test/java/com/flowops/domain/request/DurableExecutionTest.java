package com.flowops.domain.request;

import com.flowops.domain.execution.*;
import com.flowops.domain.exception.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DurableExecutionTest {
    private final Instant now=Instant.parse("2026-01-01T00:00:00Z");
    private Request approved() {
        var request = Request.restore(new Request.Snapshot(1L, null, null, RequestStatus.SUBMITTED,
                Map.of(), 0L, null, now, now, now));
        var plan = new ExecutionPlan(UUID.randomUUID(), request.getId(), 1L, "TYPE", now, now,
                ApprovalEvidence.allSkipped(1L, "requester"), List.of());
        request.approve(plan.approvalPlan(), List.of(), List.of());
        return request;
    }

    @Test void cancellationRequiresAKnownPlanAndTheFirstStartLatchNeverResets() {
        var legacy = Request.restore(new Request.Snapshot(1L, null, null, RequestStatus.APPROVED,
                Map.of(), 0L, null, now, now, now));
        assertEquals("execution-plan-unknown",assertThrows(ConflictException.class,legacy::cancel).getCode());
        assertThrows(ConflictException.class, () -> legacy.completeExecution(new Request.ExecutionCompletion(UUID.randomUUID(), 1L), now));

        var request=approved();
        request.markExecutionStarted(now.plusSeconds(1));
        request.markExecutionStarted(now.plusSeconds(2));assertEquals(now.plusSeconds(1),request.getExecutionStartedAt());
        assertEquals("execution-already-started",assertThrows(ConflictException.class,request::cancel).getCode());
        var unstarted=approved();unstarted.cancel();unstarted.cancel();
        assertEquals(RequestStatus.CANCELLED,unstarted.getStatus());
    }
    @Test void jobAttemptsKeepTheOriginalStartAndUseNewTokensWhileCancellationStaysForbidden() {
        var job=new ExecutionJob("key",1L,"ACTION",null);job.freezeInput(Map.of("resource","frozen"));
        UUID first=UUID.randomUUID();job.markStarted(now,first,now.plusSeconds(30));
        assertTrue(job.owns(first,now));assertFalse(job.owns(first,now.plusSeconds(30)));
        job.markFailed("failed",now.plusSeconds(5));assertEquals(1,job.getRetryCount());
        assertThrows(ConflictException.class,()->job.cancel(now,1L));
        UUID second=UUID.randomUUID();job.markStarted(now.plusSeconds(5),second,now.plusSeconds(35));
        assertEquals(now,job.getStartedAt());assertEquals(2,job.getAttemptCount());assertFalse(job.owns(first,now.plusSeconds(6)));
        job.markCompleted(Map.of("ok",true),now.plusSeconds(6));assertEquals(JobStatus.COMPLETED,job.getStatus());
    }
    @Test void frozenCommandsAreDeepCopiesAndCancelledJobsCannotStart() {
        Map<String,Object> nested=new LinkedHashMap<>();nested.put("value",1);nested.put("optional",null);
        var job=new ExecutionJob("key",1L,"ACTION",null);job.freezeInput(Map.of("nested",nested));nested.put("value",2);
        var command=new ActionCommand(job.getIdempotencyKey(),1L,job.getAction(),job.getInputPayload(),null);
        assertEquals(1,((Map<?,?>)command.payload().get("nested")).get("value"));
        assertThrows(UnsupportedOperationException.class,()->command.payload().clear());
        job.cancel(now,7L);assertEquals(JobStatus.CANCELLED,job.getStatus());
        assertThrows(InvalidStateTransitionException.class,()->job.markStarted(now,UUID.randomUUID(),now.plusSeconds(30)));
    }
}
