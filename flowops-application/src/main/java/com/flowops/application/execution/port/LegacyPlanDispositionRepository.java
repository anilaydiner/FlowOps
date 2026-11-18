package com.flowops.application.execution.port;

import com.flowops.application.execution.model.LegacyPlanDisposition.Entry;
import java.util.*;

public interface LegacyPlanDispositionRepository {
    boolean append(Entry entry);
    Optional<Entry> find(UUID commandId);
    boolean payloadMatches(UUID commandId,Map<String,Object> payload);
    List<Entry> history(Long requestId,int limit);
    boolean missingHistoricalJobs(Long requestId);
    boolean actionHistory(Long requestId,String action,String key);
    boolean completedEvidence(Long jobId);
    boolean providerHistoryMatches(Long jobId,String providerId);
    void bindProvider(UUID dispositionId,Long jobId,String providerId);
}
