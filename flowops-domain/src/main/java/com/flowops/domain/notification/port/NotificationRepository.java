package com.flowops.domain.notification.port;

import com.flowops.domain.notification.Notification;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for {@link Notification} records.
 */
public interface NotificationRepository {

    Notification save(Notification notification);

    List<Notification> saveAll(List<Notification> notifications);

    Optional<Notification> findById(Long id);

    PageResult<Notification> findByRecipientId(Long recipientId, PageQuery query);

    long countUnread(Long recipientId);

    List<Notification> findUnread(Long recipientId);

    void deleteAllByRecipientId(Long recipientId);

    /** All notifications for a recipient (unpaged). */
    List<Notification> findByRecipientId(Long recipientId);
}
