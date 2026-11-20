package com.flowops.application.execution.port;

import com.flowops.application.execution.model.ExecutionAttempt;
import com.flowops.application.execution.model.ExecutionAttempt.*;
import java.util.*;

public interface ExecutionAttemptRepository {

    void append(ExecutionAttempt attempt);

    void append(Event event);

    void observeBaseline(Baseline baseline);

    Optional<ExecutionAttempt> find(UUID token);

    Optional<Baseline> baseline(Long jobId);

    List<ExecutionAttempt> attempts(Long requestId, Long jobId, Integer beforeAttempt, int limit);

    List<Event> events(Long requestId, Long jobId, Long beforeSequence, int limit);

}
