package com.flowops.infra.persistence;

import com.flowops.domain.Clarification;
import com.flowops.domain.port.ClarificationRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class SpringClarificationRepository implements ClarificationRepository {

    private final ClarificationJpaRepository jpaRepository;

    public SpringClarificationRepository(ClarificationJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Clarification save(Clarification clarification) {
        return jpaRepository.save(clarification);
    }

    @Override
    public List<Clarification> findByRequestIdOrderByCreatedAtDesc(Long requestId) {
        return jpaRepository.findByRequestIdOrderByCreatedAtDesc(requestId);
    }

    @Override
    public Optional<Clarification> findByIdAndRequestId(Long id, Long requestId) {
        return jpaRepository.findByIdAndRequestId(id, requestId);
    }

    @Override
    public long countByRequestId(Long requestId) {
        return jpaRepository.countByRequestId(requestId);
    }
}
