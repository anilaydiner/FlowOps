package com.flowops.infra.persistence;

import com.flowops.domain.Workflow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface WorkflowJpaRepository extends JpaRepository<Workflow, Long>, JpaSpecificationExecutor<Workflow> {

    Optional<Workflow> findByCode(String code);

    boolean existsByCode(String code);
}
