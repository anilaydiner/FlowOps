package com.flowops.persistence;

import com.flowops.persistence.entity.PasswordResetTokenEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordResetTokenJpaRepository extends JpaRepository<PasswordResetTokenEntity, Long> {

    Optional<PasswordResetTokenEntity> findByTokenHash(String tokenHash);

    List<PasswordResetTokenEntity> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    void deleteAllByUserId(Long userId);
}
