package com.flowops.domain.request.port;

import com.flowops.domain.request.Request;
import java.util.Optional;

/**
 * Persistence port for the {@link Request} aggregate.
 *
 * <p>Implemented by the JPA adapter in {@code persistence}; the domain
 * layer only sees this contract — no Spring Data types.
 */
public interface RequestRepository {

    /** Save and check the request version before returning. */
    Request save(Request request);

    Optional<Request> findById(Long id);

    /**
     * Lock the parent row until transaction completion and reload its current state.
     * Requires an existing write transaction. Call before modifying the request or its children;
     * reloading may discard cached entity state. Acquire multiple request locks in ascending id order.
     */
    Optional<Request> findByIdForUpdate(Long id);

    /** Nonblocking parent-first acquisition for background work. Requires a write transaction. */
    Optional<Request> tryFindByIdForUpdate(Long id);

}
