package com.flowops.infra.persistence;

import com.flowops.domain.RequestType;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.port.RequestTypeRepository;
import com.flowops.domain.query.RequestTypeFilter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class SpringRequestTypeRepository implements RequestTypeRepository {

    private final RequestTypeJpaRepository jpaRepository;

    public SpringRequestTypeRepository(RequestTypeJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public RequestType save(RequestType type) {
        return jpaRepository.save(type);
    }

    @Override
    public Optional<RequestType> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public PageResult<RequestType> findAll(RequestTypeFilter filter, PageQuery query) {
        Pageable pageable = Paging.toPageable(query);
        Specification<RequestType> spec = RequestTypeSpecifications.from(filter);
        Page<RequestType> page = (spec == null)
                ? jpaRepository.findAll(pageable)
                : jpaRepository.findAll(spec, pageable);
        return Paging.toPageResult(page);
    }

    @Override
    public Optional<RequestType> findByCode(String code) {
        return jpaRepository.findByCode(code);
    }

    @Override
    public boolean existsByCode(String code) {
        return jpaRepository.existsByCode(code);
    }
}
