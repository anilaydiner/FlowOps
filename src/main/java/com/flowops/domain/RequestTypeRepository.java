package com.flowops.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface RequestTypeRepository extends JpaRepository<RequestType, Long>, JpaSpecificationExecutor<RequestType> {

    Optional<RequestType> findByCode(String code);

    boolean existsByCode(String code);
}
