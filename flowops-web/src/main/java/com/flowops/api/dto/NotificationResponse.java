package com.flowops.api.dto;

import com.flowops.domain.notification.Notification;
import java.time.Instant;

public record NotificationResponse(Long id, Long recipientId, String type, String title, String body, Long requestId,
        boolean read, Instant readAt, Instant createdAt) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(n.getId(), n.getRecipientId(), n.getType(), n.getTitle(), n.getBody(),
                n.getRequestId(), n.isRead(), n.getReadAt(), n.getCreatedAt());
    }
}
