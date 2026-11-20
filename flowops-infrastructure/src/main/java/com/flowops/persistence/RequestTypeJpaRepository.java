package com.flowops.persistence;

import com.flowops.persistence.entity.RequestTypeEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface RequestTypeJpaRepository
        extends JpaRepository<RequestTypeEntity, Long>, JpaSpecificationExecutor<RequestTypeEntity> {

    Optional<RequestTypeEntity> findByCode(String code);

    boolean existsByCode(String code);

}
