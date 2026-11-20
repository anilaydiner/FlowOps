package com.flowops.persistence;

import com.flowops.persistence.entity.EmailLogEntryEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmailLogEntryJpaRepository extends JpaRepository<EmailLogEntryEntity, Long> {

    List<EmailLogEntryEntity> findByToAddressOrderByCreatedAtDesc(String toAddress);

    Optional<EmailLogEntryEntity> findFirstByOutboxId(Long outboxId);

    long countByStatus(String status);

}
