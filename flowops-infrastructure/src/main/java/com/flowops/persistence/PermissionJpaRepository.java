package com.flowops.persistence;

import com.flowops.persistence.entity.PermissionEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PermissionJpaRepository
        extends JpaRepository<PermissionEntity, Long>, JpaSpecificationExecutor<PermissionEntity> {

    Optional<PermissionEntity> findByCode(String code);

    boolean existsByCode(String code);

}
