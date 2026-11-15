package com.flowops.infra.persistence;

import com.flowops.domain.Notification;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.port.NotificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class SpringNotificationRepository implements NotificationRepository {

    private final NotificationJpaRepository jpaRepository;

    public SpringNotificationRepository(NotificationJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Notification save(Notification notification) {
        return jpaRepository.save(notification);
    }

    @Override
    public List<Notification> saveAll(List<Notification> notifications) {
        return jpaRepository.saveAll(notifications);
    }

    @Override
    public Optional<Notification> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public PageResult<Notification> findByRecipientId(Long recipientId, PageQuery query) {
        Pageable pageable = Paging.toPageable(query);
        Page<Notification> page = jpaRepository.findByRecipientId(recipientId, pageable);
        return Paging.toPageResult(page);
    }

    @Override
    public long countUnread(Long recipientId) {
        return jpaRepository.countUnread(recipientId);
    }

    @Override
    public List<Notification> findUnread(Long recipientId) {
        return jpaRepository.findUnread(recipientId);
    }

    @Override
    public void deleteAllByRecipientId(Long recipientId) {
        jpaRepository.deleteAllByRecipientId(recipientId);
    }
}
