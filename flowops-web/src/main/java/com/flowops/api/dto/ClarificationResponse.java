package com.flowops.api.dto;

import com.flowops.domain.request.Clarification;
import java.time.Instant;

public record ClarificationResponse(Long id, Long requestId, Long askedBy, String question, String answer,
        Long answeredBy, Instant answeredAt, Instant createdAt) {
    public static ClarificationResponse from(Clarification c) {
        return new ClarificationResponse(c.getId(), c.getRequestId(), c.getAskedBy(), c.getQuestion(), c.getAnswer(),
                c.getAnsweredBy(), c.getAnsweredAt(), c.getCreatedAt());
    }
}
