package com.flowops.infra.persistence;

import com.flowops.domain.PasswordResetToken;
import com.flowops.domain.port.PasswordResetTokenRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class SpringPasswordResetTokenRepository implements PasswordResetTokenRepository {

    private final PasswordResetTokenJpaRepository jpaRepository;

    public SpringPasswordResetTokenRepository(PasswordResetTokenJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public PasswordResetToken save(PasswordResetToken token) {
        return jpaRepository.save(token);
    }

    @Override
    public Optional<PasswordResetToken> findByTokenHash(String tokenHash) {
        return jpaRepository.findByTokenHash(tokenHash);
    }

    @Override
    public List<PasswordResetToken> findAllByUserId(Long userId) {
        return jpaRepository.findAllByUserIdOrderByCreatedAtDesc(userId);
    }

    @Override
    public void deleteByUserId(Long userId) {
        jpaRepository.deleteAllByUserId(userId);
    }
}
