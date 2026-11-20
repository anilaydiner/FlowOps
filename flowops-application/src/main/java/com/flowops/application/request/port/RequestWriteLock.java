package com.flowops.application.request.port;

import com.flowops.domain.request.Request;
import java.util.Optional;

/** Acquires parent request locks for application write operations. */
public interface RequestWriteLock {

    /**
     * Lock the request until the current write transaction completes and reload its
     * state. Call before modifying the request or its children. Reloading may discard
     * cached entity state. Acquire multiple request locks in ascending ID order.
     * @return the refreshed request, or empty if it does not exist
     */
    Optional<Request> findByIdForUpdate(Long id);

    /**
     * Acquire the same lock without waiting for another transaction to release it.
     * Requires an existing write transaction and follows the same ordering and reload
     * rules.
     * @return the refreshed request, or empty if it does not exist or is already locked
     */
    Optional<Request> tryFindByIdForUpdate(Long id);

}
