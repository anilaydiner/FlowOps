package com.flowops.domain.port;

import com.flowops.domain.Request;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.query.RequestFilter;

import java.util.Collection;
import java.util.Optional;

/**
 * Persistence port for the {@link Request} aggregate.
 *
 * <p>Implemented by the JPA adapter in {@code infra.persistence}; the domain
 * layer only sees this contract — no Spring Data types.
 */
public interface RequestRepository {

    Request save(Request request);

    Optional<Request> findById(Long id);

    /**
     * Flush pending writes of the persistence context.
     * Used to surface optimistic-lock conflicts before side-effect writes
     * (outbox/audit) are issued.
     */
    void flush();

    /**
     * Filtered, paged request listing.
     *
     * @param filter          optional status/type filters (may be null)
     * @param query           paging + sort (fields whitelisted by the caller)
     * @param callerId        the authenticated caller's user id
     * @param callerIsAdmin   admins see all requests; others only their own
     */
    PageResult<Request> findAll(RequestFilter filter, PageQuery query, Long callerId, boolean callerIsAdmin);

    /**
     * Paged requests with one of the given ids and status {@code IN_REVIEW}
     * (the approver queue: ids already pre-filtered by step eligibility).
     */
    PageResult<Request> findQueuePage(Collection<Long> requestIds, PageQuery query);
}
