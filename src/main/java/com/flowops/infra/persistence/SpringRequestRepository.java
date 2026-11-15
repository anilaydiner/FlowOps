package com.flowops.infra.persistence;

import com.flowops.domain.Request;
import com.flowops.domain.RequestStatus;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.port.RequestRepository;
import com.flowops.domain.query.RequestFilter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;

/** JPA adapter for the {@link RequestRepository} port. */
@Repository
public class SpringRequestRepository implements RequestRepository {

    private final RequestJpaRepository jpaRepository;

    public SpringRequestRepository(RequestJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public Request save(Request request) {
        return jpaRepository.save(request);
    }

    @Override
    public Optional<Request> findById(Long id) {
        return jpaRepository.findById(id);
    }

    @Override
    public void flush() {
        jpaRepository.flush();
    }

    @Override
    public PageResult<Request> findAll(RequestFilter filter, PageQuery query, Long callerId, boolean callerIsAdmin) {
        Pageable pageable = Paging.toPageable(query);
        Specification<Request> spec = RequestSpecifications.from(filter, callerId, callerIsAdmin);
        Page<Request> page = (spec == null)
                ? jpaRepository.findAll(pageable)
                : jpaRepository.findAll(spec, pageable);
        return Paging.toPageResult(page);
    }

    @Override
    public PageResult<Request> findQueuePage(Collection<Long> requestIds, PageQuery query) {
        if (requestIds.isEmpty()) {
            return PageResult.empty(query.page(), query.size());
        }
        Pageable pageable = Paging.toPageable(query);
        Specification<Request> spec = (root, cq, cb) ->
                cb.and(
                        root.get("id").in(requestIds),
                        cb.equal(root.get("status"), RequestStatus.IN_REVIEW));
        return Paging.toPageResult(jpaRepository.findAll(spec, pageable));
    }
}
