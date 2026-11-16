package com.flowops.domain;

import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
                "correlation", CREATED, SUBMITTED, SUBMITTED));
    }

    @ParameterizedTest
    @EnumSource(RequestStatus.class)
    void preservesLifecycleTransitions(RequestStatus from) {
        for (RequestStatus to : RequestStatus.values()) {
            Request request = restored(from, Map.of());
            if (EDGES.getOrDefault(from, Set.of()).contains(to)) {
                request.transitionTo(to);
                assertEquals(to, request.getStatus());
            } else {
                assertThrows(InvalidStateTransitionException.class, () -> request.transitionTo(to));
                assertEquals(from, request.getStatus());
                assertEquals(SUBMITTED, request.getUpdatedAt());
            }
        }
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
