package com.flowops.domain.execution;

/** Legacy work can be looked up without fabricating missing execution input. */
public record ProviderQuery(String idempotencyKey, Long requestId, String action) {
}
