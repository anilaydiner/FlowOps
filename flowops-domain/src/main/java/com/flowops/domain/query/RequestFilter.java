package com.flowops.domain.query;

/**
 * Optional filters for the request list endpoint.
 *
 * @param status   exact match on request.status (e.g. DRAFT)
 * @param typeCode exact match on request type code (e.g. EXPENSE)
 * @param mine     when true, restrict to the caller's own requests
 */
public record RequestFilter(
        String status,
        String typeCode,
        Boolean mine
) {
    public boolean isEmpty() {
        return status == null && typeCode == null && mine == null;
    }
}
