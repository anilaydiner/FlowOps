package com.flowops.api;

import com.flowops.api.dto.NotificationResponse;
import com.flowops.api.dto.PageResponse;
import com.flowops.domain.Notification;
import com.flowops.security.UserPrincipal;
import com.flowops.application.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import com.flowops.domain.paging.PageQuery;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    @Operation(summary = "List my notifications",
            description = "Returns the authenticated user's notification feed, newest first. " +
                    "Supports filtering by read status and pagination.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paged notification list"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Missing permission")
    })
    public PageResponse<NotificationResponse> list(
            @Parameter(description = "When true, return only unread notifications")
            @RequestParam(required = false) Boolean unread,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal user
    ) {
        int safeSize = Math.min(Math.max(1, size), 100);
        PageQuery pageQuery = new PageQuery(page, safeSize, List.of(new PageQuery.Order("createdAt", true)));
        if (Boolean.TRUE.equals(unread)) {
            // Fetch all unread, then manually page (small dataset expected)
            List<Notification> allUnread = notifications.findUnread(user.id(), user.toIdentity());
            int from = Math.min(page * safeSize, allUnread.size());
            int to = Math.min(from + safeSize, allUnread.size());
            List<Notification> slice = allUnread.subList(from, to);
            int total = allUnread.size();
            int totalPages = (int) Math.ceil((double) total / safeSize);
            return new PageResponse<>(
                    slice.stream().map(NotificationResponse::from).toList(),
                    page, safeSize, total, totalPages,
                    page == 0, page >= totalPages - 1
            );
        } else {
            return PageResponse.from(notifications.list(user.id(), pageQuery, user.toIdentity()).map(NotificationResponse::from));
        }
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Get unread notification count")
    public UnreadCount unreadCount(@AuthenticationPrincipal UserPrincipal user) {
        return new UnreadCount(notifications.countUnread(user.id(), user.toIdentity()));
    }

    @PatchMapping("/{id}/read")
    @Operation(summary = "Mark notification as read")
    public NotificationResponse markRead(@PathVariable Long id,
                                          @AuthenticationPrincipal UserPrincipal user) {
        return NotificationResponse.from(notifications.markRead(id, user.id(), user.toIdentity()));
    }

    @PatchMapping("/read-all")
    @Operation(summary = "Mark all notifications as read")
    public UnreadCount markAllRead(@AuthenticationPrincipal UserPrincipal user) {
        notifications.markAllRead(user.id(), user.toIdentity());
        return new UnreadCount(0);
    }

    public record UnreadCount(long count) {}
}
