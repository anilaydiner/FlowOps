package com.flowops.domain.request;

import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.InvalidStateTransitionException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.*;

class RequestTest {
    private static final Instant CREATED = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant SUBMITTED = CREATED.plusSeconds(60);
    private static final Map<RequestStatus, Set<RequestStatus>> EDGES = Map.of(
            RequestStatus.DRAFT, Set.of(RequestStatus.SUBMITTED, RequestStatus.CANCELLED),
            RequestStatus.SUBMITTED, Set.of(RequestStatus.IN_REVIEW, RequestStatus.APPROVED,
                    RequestStatus.CANCELLED, RequestStatus.EXPIRED),
            RequestStatus.IN_REVIEW, Set.of(RequestStatus.APPROVED, RequestStatus.REJECTED,
                    RequestStatus.CANCELLED, RequestStatus.EXPIRED),
            RequestStatus.APPROVED, Set.of(RequestStatus.EXECUTED, RequestStatus.CANCELLED),
            RequestStatus.EXECUTED, Set.of(RequestStatus.CLOSED),
            RequestStatus.REJECTED, Set.of(RequestStatus.CLOSED));

    private Request restored(RequestStatus status, Map<String, Object> payload) {
        return Request.restore(new Request.Snapshot(12L, null, null, status, payload, 3L,
                "correlation", CREATED, SUBMITTED, SUBMITTED, status==RequestStatus.APPROVED?SUBMITTED:null,null,false,false,status==RequestStatus.APPROVED?java.util.UUID.randomUUID():null));
    }

    @ParameterizedTest
    @EnumSource(RequestStatus.class)
    void preservesLifecycleTransitions(RequestStatus from) {
        for (RequestStatus to : RequestStatus.values()) {
            if (to == RequestStatus.DRAFT || to == RequestStatus.APPROVED || to == RequestStatus.EXECUTED) continue;
            if (from == RequestStatus.CANCELLED && to == RequestStatus.CANCELLED) continue;
            Request request = restored(from, Map.of());
            if (EDGES.getOrDefault(from, Set.of()).contains(to)) {
                apply(request, to);
                assertEquals(to, request.getStatus());
            } else {
                assertThrows(InvalidStateTransitionException.class, () -> apply(request, to));
                assertEquals(from, request.getStatus());
                assertEquals(SUBMITTED, request.getUpdatedAt());
            }
        }
    }

    private static void apply(Request request, RequestStatus to) {
        switch (to) {
            case SUBMITTED -> request.markSubmitted(SUBMITTED);
            case IN_REVIEW -> request.beginReview(SUBMITTED);
            case REJECTED -> request.reject(SUBMITTED);
            case CANCELLED -> request.cancel(SUBMITTED);
            case EXPIRED -> request.expire(SUBMITTED);
            case CLOSED -> request.close(SUBMITTED);
            default -> throw new AssertionError(to);
        }
    }

    @Test
    void approvalAndExecutionRequireEvidenceForThisRequest() {
        var request = restored(RequestStatus.SUBMITTED, Map.of());
        var planId = java.util.UUID.randomUUID();
        var evidence = ApprovalEvidence.allSkipped(1L, "requester");
        var wrongPlan = new com.flowops.domain.execution.ExecutionPlan(planId, 99L, 1L, "TYPE",
                CREATED, SUBMITTED, evidence, List.of());
        assertThrows(ConflictException.class, () -> request.approve(wrongPlan.approvalPlan(), List.of(), List.of()));

        var plan = new com.flowops.domain.execution.ExecutionPlan(planId, request.getId(), 1L, "TYPE",
                CREATED, SUBMITTED, evidence, List.of());
        request.approve(plan.approvalPlan(), List.of(), List.of());
        assertEquals(planId, request.getExecutionPlanId());
        assertThrows(ConflictException.class, () -> request.completeExecution(
                new Request.ExecutionCompletion(java.util.UUID.randomUUID(), request.getId()), SUBMITTED));
        request.completeExecution(new Request.ExecutionCompletion(planId, request.getId()), SUBMITTED);
        assertEquals(RequestStatus.EXECUTED, request.getStatus());
    }

    @ParameterizedTest
    @EnumSource(RequestStatus.class)
    void onlyDraftsCanReplacePayload(RequestStatus status) {
        Request request = restored(status, Map.of("old", 1));
        if (status == RequestStatus.DRAFT) {
            request.replacePayload(Map.of("new", 2));
            assertEquals(Map.of("new", 2), request.getPayload());
        } else {
            assertThrows(ConflictException.class, () -> request.replacePayload(Map.of("new", 2)));
            assertEquals(Map.of("old", 1), request.getPayload());
        }
    }

    @Test
    void submissionPreservesSuppliedTimestamp() {
        Request request = Request.newDraft(null, null, null, "correlation");
        assertEquals(RequestStatus.DRAFT, request.getStatus());
        assertEquals(request.getCreatedAt(), request.getUpdatedAt());
        request.markSubmitted(SUBMITTED);
        assertEquals(RequestStatus.SUBMITTED, request.getStatus());
        assertEquals(SUBMITTED, request.getSubmittedAt());
        assertEquals(SUBMITTED, request.getUpdatedAt());
    }

    @Test
    @SuppressWarnings("unchecked")
    void nestedJsonCannotBypassPayloadEditRules() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("amount", 10);
        item.put("optional", null);
        List<Object> items = new ArrayList<>(List.of(item));
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("items", items);
        Request request = Request.newDraft(null, null, input, null);
        item.put("amount", 999);
        items.clear();
        input.clear();
        List<Object> stored = (List<Object>) request.getPayload().get("items");
        Map<String, Object> storedItem = (Map<String, Object>) stored.getFirst();
        assertEquals(10, storedItem.get("amount"));
        assertTrue(storedItem.containsKey("optional"));
        assertNull(storedItem.get("optional"));
        request.markSubmitted(SUBMITTED);
        assertThrows(UnsupportedOperationException.class, () -> request.getPayload().clear());
        assertThrows(UnsupportedOperationException.class, () -> stored.add("extra"));
        assertThrows(UnsupportedOperationException.class, () -> storedItem.put("amount", 999));
    }

    @Test
    void restorationAndReplacementAlsoCopyPayload() {
        Map<String, Object> nested = new LinkedHashMap<>(Map.of("value", 1));
        Map<String, Object> input = Map.of("nested", nested);
        Request restored = restored(RequestStatus.DRAFT, input);
        Request draft = Request.newDraft(null, null, null, null);
        draft.replacePayload(input);
        nested.put("value", 2);
        assertEquals(Map.of("nested", Map.of("value", 1)), restored.getPayload());
        assertEquals(restored.getPayload(), draft.getPayload());
        draft.replacePayload(null);
        assertTrue(draft.getPayload().isEmpty());
    }

    @Test
    void restorationAndPersistenceAcknowledgementPreserveBusinessState() {
        Request request = restored(RequestStatus.IN_REVIEW, Map.of("amount", 10));
        assertEquals(12L, request.getId());
        assertEquals(3L, request.getVersion());
        request.recordPersistence(12L, 4L);
        assertEquals(4L, request.getVersion());
        assertEquals(RequestStatus.IN_REVIEW, request.getStatus());
        assertEquals("correlation", request.getCorrelationId());
        assertEquals(CREATED, request.getCreatedAt());
        assertEquals(SUBMITTED, request.getSubmittedAt());
        assertEquals(SUBMITTED, request.getUpdatedAt());
        assertThrows(IllegalArgumentException.class, () -> request.recordPersistence(13L, 5L));
        assertEquals(12L, request.getId());
        assertEquals(4L, request.getVersion());
    }
}
