package com.flowops.application.notification;

import com.flowops.domain.exception.ForbiddenException;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.policy.AuthorizationEvaluator;
import com.flowops.domain.identity.policy.PermissionCodes;
import com.flowops.domain.notification.Notification;
import com.flowops.domain.notification.port.NotificationRepository;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {

    private final NotificationRepository notifications;
    private final AuthorizationEvaluator authz;

    public NotificationService(NotificationRepository notifications, AuthorizationEvaluator authz) {
        this.notifications = notifications;
        this.authz = authz;
    }

    @Transactional(readOnly = true)
    public PageResult<Notification> list(Long recipientId, PageQuery query, CallerIdentity caller) {
        authz.require(caller, PermissionCodes.NOTIFICATIONS_READ);
        return notifications.findByRecipientId(recipientId, query);
    }

    @Transactional(readOnly = true)
    public List<Notification> findUnread(Long recipientId, CallerIdentity caller) {
        authz.require(caller, PermissionCodes.NOTIFICATIONS_READ);
        return notifications.findUnread(recipientId);
    }

    @Transactional(readOnly = true)
    public long countUnread(Long recipientId, CallerIdentity caller) {
        authz.require(caller, PermissionCodes.NOTIFICATIONS_READ);
        return notifications.countUnread(recipientId);
    }

    @Transactional
    public Notification markRead(Long id, Long recipientId, CallerIdentity caller) {
        authz.require(caller, PermissionCodes.NOTIFICATIONS_UPDATE);
        Notification notification = notifications.findById(id)
                .orElseThrow(() -> new NotFoundException("Notification not found: " + id));
        if (!notification.getRecipientId().equals(recipientId)) {
            throw new ForbiddenException("Not your notification");
        }
        notification.markRead();
        return notifications.save(notification);
    }

    @Transactional
    public int markAllRead(Long recipientId, CallerIdentity caller) {
        authz.require(caller, PermissionCodes.NOTIFICATIONS_UPDATE);
        List<Notification> unread = notifications.findUnread(recipientId);
        unread.forEach(Notification::markRead);
        notifications.saveAll(unread);
        return unread.size();
    }
}
