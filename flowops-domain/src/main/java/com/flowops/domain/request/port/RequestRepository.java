package com.flowops.domain.request.port;

import com.flowops.domain.request.Request;
import java.util.Optional;

/**
 * Persistence port for the {@link Request} aggregate.
 *
 * <p>
 * Implemented by the JPA adapter in {@code persistence}; the domain layer only sees this
 * contract — no Spring Data types.
 */
public interface RequestRepository {

    /** Save and check the request version before returning. */
    Request save(Request request);

    Optional<Request> findById(Long id);

}
