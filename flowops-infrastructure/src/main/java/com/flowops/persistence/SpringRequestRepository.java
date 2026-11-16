package com.flowops.persistence;

import com.flowops.domain.request.Request;
import com.flowops.domain.request.port.RequestRepository;
import com.flowops.persistence.entity.RequestEntity;
import com.flowops.persistence.mapping.RequestMappers;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/** JPA adapter for the {@link RequestRepository} port. Maps domain ↔ entity. */
@Repository
public class SpringRequestRepository implements RequestRepository {

    private final RequestJpaRepository jpaRepository;

    public SpringRequestRepository(RequestJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Request save(Request request) {
        RequestEntity saved = jpaRepository.saveAndFlush(RequestMappers.toEntity(request));
        backfill(request, saved);
        return request;
    }

    @Override
    public Optional<Request> findById(Long id) {
        return jpaRepository.findById(id).map(RequestMappers::fromEntity);
    }

    @Override
    public void flush() {
        jpaRepository.flush();
    }

    private static void backfill(Request d, RequestEntity e) {
        d.recordPersistence(e.getId(), e.getVersion());
    }
}
