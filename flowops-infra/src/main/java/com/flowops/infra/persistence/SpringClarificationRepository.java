package com.flowops.infra.persistence;

import com.flowops.domain.Clarification;
import com.flowops.domain.port.ClarificationRepository;
import com.flowops.infra.persistence.entity.ClarificationEntity;
import com.flowops.infra.persistence.mapping.ClarificationMappers;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** JPA adapter for the {@link ClarificationRepository} port. Maps domain ↔ entity. */
@Repository
public class SpringClarificationRepository implements ClarificationRepository {

    private final ClarificationJpaRepository jpaRepository;

    public SpringClarificationRepository(ClarificationJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Clarification save(Clarification clarification) {
        ClarificationEntity saved = jpaRepository.saveAndFlush(ClarificationMappers.toEntity(clarification));
        clarification.setId(saved.getId());
        return clarification;
    }

    @Override
    public List<Clarification> findByRequestIdOrderByCreatedAtDesc(Long requestId) {
        return jpaRepository.findByRequestIdOrderByCreatedAtDesc(requestId)
                .stream().map(ClarificationMappers::fromEntity).toList();
    }

    @Override
    public Optional<Clarification> findByIdAndRequestId(Long id, Long requestId) {
        return jpaRepository.findByIdAndRequestId(id, requestId).map(ClarificationMappers::fromEntity);
    }

    @Override
    public long countByRequestId(Long requestId) {
        return jpaRepository.countByRequestId(requestId);
    }

    @Override
    public Optional<Clarification> findById(Long id) {
        return jpaRepository.findById(id).map(ClarificationMappers::fromEntity);
    }
}
