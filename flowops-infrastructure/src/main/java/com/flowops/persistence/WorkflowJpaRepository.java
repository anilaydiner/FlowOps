package com.flowops.persistence;

import com.flowops.persistence.entity.WorkflowEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface WorkflowJpaRepository extends JpaRepository<WorkflowEntity, Long>, JpaSpecificationExecutor<WorkflowEntity> {

    Optional<WorkflowEntity> findByCode(String code);

    boolean existsByCode(String code);
}
