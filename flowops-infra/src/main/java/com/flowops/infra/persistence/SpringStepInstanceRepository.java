package com.flowops.infra.persistence;

import com.flowops.domain.DecisionAction;
import com.flowops.domain.RequestStatus;
import com.flowops.domain.StepInstance;
import com.flowops.domain.StepState;
import com.flowops.domain.port.StepInstanceRepository;
import com.flowops.infra.persistence.entity.StepInstanceEntity;
import com.flowops.infra.persistence.mapping.StepInstanceMappers;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/** JPA adapter for the {@link StepInstanceRepository} port. Maps domain ↔ entity. */
@Repository
public class SpringStepInstanceRepository implements StepInstanceRepository {

    private final StepInstanceJpaRepository jpaRepository;

    public SpringStepInstanceRepository(StepInstanceJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public StepInstance save(StepInstance step) {
        StepInstanceEntity saved = jpaRepository.saveAndFlush(StepInstanceMappers.toEntity(step));
        step.setId(saved.getId());
        return step;
    }

    @Override
    public List<StepInstance> findByRequestIdOrderById(Long requestId) {
        return jpaRepository.findByRequestIdOrderById(requestId).stream().map(StepInstanceMappers::fromEntity).toList();
    }

    @Override
    public Optional<StepInstance> findTopByRequestIdAndStateOrderById(Long requestId, StepState state) {
        return jpaRepository.findTopByRequestIdAndStateOrderById(requestId, state).map(StepInstanceMappers::fromEntity);
    }

    @Override
    public long countByRequestIdAndState(Long requestId, StepState state) {
        return jpaRepository.countByRequestIdAndState(requestId, state);
    }

    @Override
    public List<StepInstance> findByState(StepState state) {
        return jpaRepository.findByState(state).stream().map(StepInstanceMappers::fromEntity).toList();
    }

    @Override
    public List<StepInstance> findByStateWithAssociations(StepState state) {
        return jpaRepository.findByStateWithAssociations(state).stream().map(StepInstanceMappers::fromEntity).toList();
    }

    @Override
    public List<Long> findEligibleRequestIds(StepState state, RequestStatus status,
                                             Collection<String> roles, Long userId) {
        return jpaRepository.findEligibleRequestIds(state, status, roles, DecisionAction.DELEGATE, userId);
    }

    @Override
    public void backdateCreatedAt(Long id, Instant ts) {
        jpaRepository.backdateCreatedAt(id, ts);
    }
}
