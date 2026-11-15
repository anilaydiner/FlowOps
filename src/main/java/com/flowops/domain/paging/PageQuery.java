package com.flowops.domain.paging;

import java.util.List;

/**
 * Framework-free paging + sort descriptor used by repository ports.
 *
 * <p>Persistence adapters translate this into their native paging type
 * (e.g. JPA {@code Pageable}). Sort fields must be whitelisted by the
 * caller before reaching an adapter (injection guard).
 *
 * @param page   zero-based page number
 * @param size   page size
 * @param orders sort orders, applied in sequence (defaults to {@code id asc})
 */
public record PageQuery(int page, int size, List<Order> orders) {

    public PageQuery {
        orders = (orders == null || orders.isEmpty())
                ? List.of(new Order("id", false))
                : List.copyOf(orders);
    }

    /** First page with an explicit size, default ordering. */
    public static PageQuery first(int size) {
        return new PageQuery(0, size, List.of());
    }

    /** Explicit page/size with a single sort order. */
    public static PageQuery of(int page, int size, String field, boolean descending) {
        return new PageQuery(page, size, List.of(new Order(field, descending)));
    }

    /** A single sort order on a whitelisted entity attribute. */
    public record Order(String field, boolean descending) {
    }
}
