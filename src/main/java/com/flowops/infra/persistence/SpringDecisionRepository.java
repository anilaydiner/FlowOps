package com.flowops.infra.persistence;

import com.flowops.domain.Decision;
import com.flowops.domain.DecisionAction;
import com.flowops.domain.port.DecisionRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class SpringDecisionRepository implements DecisionRepository {

    private final DecisionJpaRepository jpaRepository;

    public SpringDecisionRepository(DecisionJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Decision save(Decision decision) {
        return jpaRepository.save(decision);
    }

    @Override
    public List<Decision> findByStepInstanceIdOrderById(Long stepInstanceId) {
        return jpaRepository.findByStepInstanceIdOrderById(stepInstanceId);
    }

    @Override
    public long countByStepInstanceIdAndAction(Long stepInstanceId, DecisionAction action) {
        return jpaRepository.countByStepInstanceIdAndAction(stepInstanceId, action);
    }
}
