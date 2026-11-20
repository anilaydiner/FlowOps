package com.flowops.application.audit.port;

import com.flowops.application.audit.model.LifecycleAuditRecord;
import java.time.Instant;
import java.util.List;

public interface LifecycleAuditRepository {

    boolean beginCoverage(Long requestId, Instant at, boolean legacyPrefix);

    boolean hasCoverage(Long requestId);

    boolean appendIfAbsent(LifecycleAuditRecord record);

    List<LifecycleAuditRecord> findByRequestId(Long requestId);

}
