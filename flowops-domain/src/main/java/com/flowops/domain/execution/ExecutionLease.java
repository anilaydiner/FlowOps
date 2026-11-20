package com.flowops.domain.execution;

import java.util.UUID;

/** Internal attempt identity; never exposed as a caller-controlled HTTP renewal token. */
public record ExecutionLease(Long requestId, Long jobId, int attemptNumber, UUID token) {
}
