package com.flowops.infra.persistence;

import com.flowops.domain.Notification;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.port.NotificationRepository;
import com.flowops.infra.persistence.entity.NotificationEntity;
import com.flowops.infra.persistence.mapping.NotificationMappers;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** JPA adapter for the {@link NotificationRepository} port. Maps domain ↔ entity. */
@Repository
public class SpringNotificationRepository implements NotificationRepository {

    private final NotificationJpaRepository jpaRepository;

    public SpringNotificationRepository(NotificationJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Notification save(Notification notification) {
        NotificationEntity saved = jpaRepository.saveAndFlush(NotificationMappers.toEntity(notification));
        backfill(notification, saved);
        return notification;
    }

    @Override
    public List<Notification> saveAll(List<Notification> notifications) {
        List<NotificationEntity> saved = jpaRepository.saveAllAndFlush(
                notifications.stream().map(NotificationMappers::toEntity).toList());
        for (int i = 0; i < notifications.size(); i++) {
            notifications.get(i).setId(saved.get(i).getId());
        }
        return notifications;
    }

    @Override
    public Optional<Notification> findById(Long id) {
        return jpaRepository.findById(id).map(NotificationMappers::fromEntity);
    }

    @Override
    public PageResult<Notification> findByRecipientId(Long recipientId, PageQuery query) {
        Pageable pageable = Paging.toPageable(query);
        Page<NotificationEntity> page = jpaRepository.findByRecipientId(recipientId, pageable);
        return Paging.toPageResult(page.map(NotificationMappers::fromEntity));
    }

    @Override
    public long countUnread(Long recipientId) {
        return jpaRepository.countUnread(recipientId);
    }

    @Override
    public List<Notification> findUnread(Long recipientId) {
        return jpaRepository.findUnread(recipientId).stream().map(NotificationMappers::fromEntity).toList();
    }

    @Override
    public void deleteAllByRecipientId(Long recipientId) {
        jpaRepository.deleteAllByRecipientId(recipientId);
    }

    @Override
    public List<Notification> findByRecipientId(Long recipientId) {
        return jpaRepository.findByRecipientId(recipientId, Pageable.unpaged())
                .map(NotificationMappers::fromEntity).toList();
    }

    private static void backfill(Notification d, NotificationEntity e) {
        d.setId(e.getId());
    }
}
