package com.flowops.infra.persistence;

import com.flowops.domain.Request;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

/** Spring Data repository for {@link Request} — implementation detail of the JPA adapter. */
public interface RequestJpaRepository extends JpaRepository<Request, Long>, JpaSpecificationExecutor<Request> {
}
