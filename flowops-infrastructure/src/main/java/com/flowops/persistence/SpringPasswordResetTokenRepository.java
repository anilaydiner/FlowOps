package com.flowops.persistence;

import com.flowops.domain.identity.PasswordResetToken;
import com.flowops.domain.identity.port.PasswordResetTokenRepository;
import com.flowops.persistence.entity.PasswordResetTokenEntity;
import com.flowops.persistence.mapping.PasswordResetTokenMappers;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * JPA adapter for the {@link PasswordResetTokenRepository} port. Maps domain ↔ entity.
 */
@Repository
public class SpringPasswordResetTokenRepository implements PasswordResetTokenRepository {

    private final PasswordResetTokenJpaRepository jpaRepository;

    public SpringPasswordResetTokenRepository(PasswordResetTokenJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public PasswordResetToken save(PasswordResetToken token) {
        PasswordResetTokenEntity saved = jpaRepository.saveAndFlush(PasswordResetTokenMappers.toEntity(token));
        token.setId(saved.getId());
        return token;
    }

    @Override
    public Optional<PasswordResetToken> findByTokenHash(String tokenHash) {
        return jpaRepository.findByTokenHash(tokenHash).map(PasswordResetTokenMappers::fromEntity);
    }

    @Override
    public List<PasswordResetToken> findAllByUserId(Long userId) {
        return jpaRepository.findAllByUserIdOrderByCreatedAtDesc(userId)
            .stream()
            .map(PasswordResetTokenMappers::fromEntity)
            .toList();
    }

    @Override
    public void deleteByUserId(Long userId) {
        jpaRepository.deleteAllByUserId(userId);
    }

}
