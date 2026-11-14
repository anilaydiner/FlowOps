package com.flowops.service;

import com.flowops.domain.Notification;
import com.flowops.domain.NotificationRepository;
import com.flowops.domain.exception.ForbiddenException;
import com.flowops.domain.exception.NotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class NotificationService {

    private final NotificationRepository notifications;

    public NotificationService(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Transactional(readOnly = true)
    public Page<Notification> list(Long recipientId, Pageable pageable) {
        return notifications.findByRecipientId(recipientId, pageable);
    }

    @Transactional(readOnly = true)
    public List<Notification> findUnread(Long recipientId) {
        return notifications.findUnread(recipientId);
    }

    @Transactional(readOnly = true)
    public long countUnread(Long recipientId) {
        return notifications.countUnread(recipientId);
    }

    @Transactional
    public Notification markRead(Long id, Long recipientId) {
        Notification notification = notifications.findById(id)
                .orElseThrow(() -> new NotFoundException("Notification not found: " + id));
        if (!notification.getRecipientId().equals(recipientId)) {
            throw new ForbiddenException("Not your notification");
        }
        notification.markRead();
        return notifications.save(notification);
    }

    @Transactional
    public int markAllRead(Long recipientId) {
        List<Notification> unread = notifications.findUnread(recipientId);
        unread.forEach(Notification::markRead);
        notifications.saveAll(unread);
        return unread.size();
    }
}
