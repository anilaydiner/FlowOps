package com.flowops.application.request.port;

import com.flowops.application.request.model.ExecutionProgress;
import java.util.*;

/**
 * Read only, after request visibility/pagination is established; implementations batch
 * all ids.
 */
public interface RequestExecutionQueries {

    Map<Long, ExecutionProgress> findByRequestIds(Collection<Long> requestIds);

}
