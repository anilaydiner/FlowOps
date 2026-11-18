package com.flowops.persistence;

import com.flowops.domain.request.Clarification;
import com.flowops.domain.request.port.ClarificationRepository;
import com.flowops.persistence.entity.ClarificationEntity;
import com.flowops.persistence.mapping.ClarificationMappers;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** JPA adapter for the {@link ClarificationRepository} port. Maps domain ↔ entity. */
@Repository
public class SpringClarificationRepository implements ClarificationRepository {

    private final ClarificationJpaRepository jpaRepository;
    private final EntityManager entities;

    public SpringClarificationRepository(ClarificationJpaRepository jpaRepository, EntityManager entities) {
        this.jpaRepository = jpaRepository;
        this.entities = entities;
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
        return jpaRepository.findByIdAndRequestId(id, requestId).map(entity -> {
            entities.refresh(entity);
            return ClarificationMappers.fromEntity(entity);
        });
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
