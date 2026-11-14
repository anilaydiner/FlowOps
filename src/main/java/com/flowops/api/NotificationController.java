package com.flowops.api;

import com.flowops.api.dto.NotificationResponse;
import com.flowops.api.dto.PageResponse;
import com.flowops.domain.Notification;
import com.flowops.domain.NotificationRepository;
import com.flowops.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationRepository notifications;

    public NotificationController(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    @Operation(summary = "List my notifications",
            description = "Returns the authenticated user's notification feed, newest first. " +
                    "Supports filtering by read status and pagination.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paged notification list"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    public PageResponse<NotificationResponse> list(
            @RequestParam(required = false) Boolean unread,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal user
    ) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "createdAt"));
        if (Boolean.TRUE.equals(unread)) {
            // Fetch all unread, then manually page (small dataset expected)
            List<Notification> allUnread = notifications.findUnread(user.id());
            int from = Math.min(page * size, allUnread.size());
            int to = Math.min(from + size, allUnread.size());
            List<Notification> slice = allUnread.subList(from, to);
            int total = allUnread.size();
            int totalPages = (int) Math.ceil((double) total / size);
            return new PageResponse<>(
                    slice.stream().map(NotificationResponse::from).toList(),
                    page, size, total, totalPages,
                    page == 0, page >= totalPages - 1
            );
        } else {
            Page<Notification> result = notifications.findByRecipientId(user.id(), pageable);
            return PageResponse.from(result.map(NotificationResponse::from));
        }
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Get unread notification count")
    public UnreadCount unreadCount(@AuthenticationPrincipal UserPrincipal user) {
        long count = notifications.countUnread(user.id());
        return new UnreadCount(count);
    }

    @PatchMapping("/{id}/read")
    @Operation(summary = "Mark notification as read")
    public NotificationResponse markRead(@PathVariable Long id,
                                          @AuthenticationPrincipal UserPrincipal user) {
        Notification notification = notifications.findById(id)
                .orElseThrow(() -> new com.flowops.service.NotFoundException("Notification not found: " + id));
        if (!notification.getRecipientId().equals(user.id())) {
            throw new com.flowops.service.ForbiddenException("Not your notification");
        }
        notification.markRead();
        notifications.save(notification);
        return NotificationResponse.from(notification);
    }

    @PatchMapping("/read-all")
    @Operation(summary = "Mark all notifications as read")
    public UnreadCount markAllRead(@AuthenticationPrincipal UserPrincipal user) {
        List<Notification> unread = notifications.findUnread(user.id());
        unread.forEach(Notification::markRead);
        notifications.saveAll(unread);
        return new UnreadCount(0);
    }

    public record UnreadCount(long count) {}
}
