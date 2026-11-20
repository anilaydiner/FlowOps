package com.flowops.domain.paging;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Framework-free paged result returned by repository ports.
 *
 * @param <T> element type
 * @param content the page of elements
 * @param page zero-based page number
 * @param size requested page size
 * @param totalElements total number of elements across all pages
 */
public record PageResult<T>(List<T> content, int page, int size, long totalElements) {

    public PageResult {
        content = List.copyOf(content);
    }

    public static <T> PageResult<T> empty(int page, int size) {
        return new PageResult<>(List.of(), page, size, 0);
    }

    public int getTotalPages() {
        return size <= 0 ? 0 : (int) Math.ceil((double) totalElements / size);
    }

    public boolean isFirst() {
        return page <= 0;
    }

    public boolean isLast() {
        return page >= getTotalPages() - 1;
    }

    public <U> PageResult<U> map(Function<? super T, ? extends U> mapper) {
        List<U> mapped = new ArrayList<>(content.size());
        for (T element : content) {
            mapped.add(mapper.apply(element));
        }
        return new PageResult<>(mapped, page, size, totalElements);
    }
}
