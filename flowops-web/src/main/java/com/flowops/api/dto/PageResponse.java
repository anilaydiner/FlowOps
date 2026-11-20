package com.flowops.api.dto;

import com.flowops.domain.paging.PageResult;
import java.util.List;

/**
 * Generic paged response envelope.
 *
 * <p>
 * Carries the page of content plus metadata so clients can drive pagination UIs (total
 * pages, hasMore, etc.) without extra round-trips.
 *
 * @param <T> element type
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages, boolean first,
        boolean last) {

    public static <T> PageResponse<T> from(PageResult<T> source) {
        return new PageResponse<>(source.content(), source.page() + 1, source.size(), source.totalElements(),
                source.getTotalPages(), source.isFirst(), source.isLast());
    }
}
