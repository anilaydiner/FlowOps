package com.flowops.infra.persistence;

import com.flowops.domain.RequestType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface RequestTypeJpaRepository extends JpaRepository<RequestType, Long>, JpaSpecificationExecutor<RequestType> {

    Optional<RequestType> findByCode(String code);

    boolean existsByCode(String code);
}
