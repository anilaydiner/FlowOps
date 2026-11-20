package com.flowops.persistence;

import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.workflow.RequestType;
import com.flowops.domain.workflow.port.RequestTypeRepository;
import com.flowops.domain.workflow.query.RequestTypeFilter;
import com.flowops.persistence.entity.RequestTypeEntity;
import com.flowops.persistence.mapping.RequestTypeMappers;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

/** JPA adapter for the {@link RequestTypeRepository} port. Maps domain ↔ entity. */
@Repository
public class SpringRequestTypeRepository implements RequestTypeRepository {

    private final RequestTypeJpaRepository jpaRepository;

    public SpringRequestTypeRepository(RequestTypeJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public RequestType save(RequestType type) {
        RequestTypeEntity saved = jpaRepository.saveAndFlush(RequestTypeMappers.toEntity(type));
        backfill(type, saved);
        return type;
    }

    @Override
    public Optional<RequestType> findById(Long id) {
        return jpaRepository.findById(id).map(RequestTypeMappers::fromEntity);
    }

    @Override
    public PageResult<RequestType> findAll(RequestTypeFilter filter, PageQuery query) {
        Pageable pageable = Paging.toPageable(query);
        Specification<RequestTypeEntity> spec = RequestTypeSpecifications.from(filter);
        Page<RequestTypeEntity> page = (spec == null) ? jpaRepository.findAll(pageable)
                : jpaRepository.findAll(spec, pageable);
        return Paging.toPageResult(page.map(RequestTypeMappers::fromEntity));
    }

    @Override
    public Optional<RequestType> findByCode(String code) {
        return jpaRepository.findByCode(code).map(RequestTypeMappers::fromEntity);
    }

    @Override
    public boolean existsByCode(String code) {
        return jpaRepository.existsByCode(code);
    }

    @Override
    public long count() {
        return jpaRepository.count();
    }

    private static void backfill(RequestType d, RequestTypeEntity e) {
        d.setId(e.getId());
    }

}
