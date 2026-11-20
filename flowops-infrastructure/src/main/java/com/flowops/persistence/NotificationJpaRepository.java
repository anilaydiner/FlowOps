package com.flowops.persistence;

import com.flowops.persistence.entity.NotificationEntity;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationJpaRepository extends JpaRepository<NotificationEntity, Long> {

    Page<NotificationEntity> findByRecipientId(Long recipientId, Pageable pageable);

    @Query("SELECT COUNT(n) FROM Notification n WHERE n.recipientId = :recipientId AND n.readAt IS NULL")
    long countUnread(@Param("recipientId") Long recipientId);

    @Query("SELECT n FROM Notification n WHERE n.recipientId = :recipientId AND n.readAt IS NULL")
    List<NotificationEntity> findUnread(@Param("recipientId") Long recipientId);

    void deleteAllByRecipientId(Long recipientId);

}
