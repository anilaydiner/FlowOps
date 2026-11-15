package com.flowops.infra.persistence;

import com.flowops.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface NotificationJpaRepository extends JpaRepository<Notification, Long> {

    Page<Notification> findByRecipientId(Long recipientId, Pageable pageable);

    @Query("SELECT COUNT(n) FROM Notification n WHERE n.recipientId = :recipientId AND n.readAt IS NULL")
    long countUnread(@Param("recipientId") Long recipientId);

    @Query("SELECT n FROM Notification n WHERE n.recipientId = :recipientId AND n.readAt IS NULL")
    List<Notification> findUnread(@Param("recipientId") Long recipientId);

    void deleteAllByRecipientId(Long recipientId);
}
