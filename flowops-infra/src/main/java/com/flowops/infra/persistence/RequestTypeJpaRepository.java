package com.flowops.infra.persistence;

import com.flowops.infra.persistence.entity.RequestTypeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface RequestTypeJpaRepository extends JpaRepository<RequestTypeEntity, Long>, JpaSpecificationExecutor<RequestTypeEntity> {

    Optional<RequestTypeEntity> findByCode(String code);

    boolean existsByCode(String code);
}
