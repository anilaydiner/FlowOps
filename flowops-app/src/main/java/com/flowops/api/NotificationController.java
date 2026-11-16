package com.flowops.api;

import com.flowops.api.dto.NotificationResponse;
import com.flowops.api.dto.PageResponse;
import com.flowops.domain.Notification;
import com.flowops.security.UserPrincipal;
import com.flowops.application.notification.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.api.web.Pagination;
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
            @Parameter(description = "One-based page number (default 1)")
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal user
    ) {
        PageQuery pageQuery = Pagination.toPageQuery(page, size, List.of("createdAt,desc"), List.of("createdAt"));
        int safeSize = pageQuery.size();
        if (Boolean.TRUE.equals(unread)) {
            // Fetch all unread, then manually page (small dataset expected)
            List<Notification> allUnread = notifications.findUnread(user.id(), user.toIdentity());
            int from = (int) Math.min((long) pageQuery.page() * safeSize, allUnread.size());
            int to = Math.min(from + safeSize, allUnread.size());
            List<Notification> slice = allUnread.subList(from, to);
            int total = allUnread.size();
            return PageResponse.from(new PageResult<>(slice, pageQuery.page(), safeSize, total)
                    .map(NotificationResponse::from));
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
