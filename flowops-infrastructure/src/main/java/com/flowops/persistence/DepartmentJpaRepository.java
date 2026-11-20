package com.flowops.persistence;

import com.flowops.persistence.entity.DepartmentEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface DepartmentJpaRepository
        extends JpaRepository<DepartmentEntity, Long>, JpaSpecificationExecutor<DepartmentEntity> {

    Optional<DepartmentEntity> findByCode(String code);

}
