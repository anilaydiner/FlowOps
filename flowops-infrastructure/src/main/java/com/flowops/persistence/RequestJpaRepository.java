package com.flowops.persistence;

import com.flowops.persistence.entity.RequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface RequestJpaRepository
        extends JpaRepository<RequestEntity, Long>, JpaSpecificationExecutor<RequestEntity> {

}
