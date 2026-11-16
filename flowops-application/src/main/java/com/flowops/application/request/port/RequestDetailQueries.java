package com.flowops.application.request.port;

import com.flowops.application.model.RequestDetailView;
import com.flowops.application.model.RequestListItem;

import java.util.Optional;

public interface RequestDetailQueries {
    /** Header only, so authorization precedes loading history and child collections. */
    Optional<RequestListItem> findRequest(Long id);

    /** Caller must authorize the header first; load collections in the same read transaction. */
    RequestDetailView load(RequestListItem request);
}
