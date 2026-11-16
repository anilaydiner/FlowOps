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

    Request save(Request request);

    Optional<Request> findById(Long id);

    /**
     * Flush pending writes of the persistence context.
     * Used to surface optimistic-lock conflicts before side-effect writes
     * (outbox/audit) are issued.
     */
    void flush();

}
