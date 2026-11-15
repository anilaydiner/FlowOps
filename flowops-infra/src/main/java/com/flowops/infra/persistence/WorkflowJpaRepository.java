package com.flowops.infra.persistence;

import com.flowops.infra.persistence.entity.WorkflowEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface WorkflowJpaRepository extends JpaRepository<WorkflowEntity, Long>, JpaSpecificationExecutor<WorkflowEntity> {

    Optional<WorkflowEntity> findByCode(String code);

    boolean existsByCode(String code);
}
