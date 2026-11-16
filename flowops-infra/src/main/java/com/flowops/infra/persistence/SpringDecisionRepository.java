package com.flowops.infra.persistence;

import com.flowops.domain.Decision;
import com.flowops.domain.DecisionAction;
import com.flowops.domain.port.DecisionRepository;
import com.flowops.infra.persistence.entity.DecisionEntity;
import com.flowops.infra.persistence.mapping.DecisionMappers;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** JPA adapter for the {@link DecisionRepository} port. Maps domain ↔ entity. */
@Repository
public class SpringDecisionRepository implements DecisionRepository {

    private final DecisionJpaRepository jpaRepository;

    public SpringDecisionRepository(DecisionJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Decision save(Decision decision) {
        DecisionEntity saved = jpaRepository.saveAndFlush(DecisionMappers.toEntity(decision));
        decision.setId(saved.getId());
        return decision;
    }

    @Override
    public List<Decision> findByStepInstanceIdOrderById(Long stepInstanceId) {
        return jpaRepository.findByStepInstanceIdOrderById(stepInstanceId)
                .stream().map(DecisionMappers::fromEntity).toList();
    }

    @Override
    public List<Decision> findByRequestIdOrderById(Long requestId) {
        return jpaRepository.findByRequestIdOrderById(requestId).stream().map(DecisionMappers::fromEntity).toList();
    }

    @Override
    public long countByStepInstanceIdAndAction(Long stepInstanceId, DecisionAction action) {
        return jpaRepository.countByStepInstanceIdAndAction(stepInstanceId, action);
    }

    @Override
    public List<Decision> findAll() {
        return jpaRepository.findAll().stream().map(DecisionMappers::fromEntity).toList();
    }

    @Override
    public Optional<Decision> findById(Long id) {
        return jpaRepository.findById(id).map(DecisionMappers::fromEntity);
    }
}
