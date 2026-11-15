package com.flowops.infra.persistence;

import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates framework-free paging types ({@code domain.paging}) to and from
 * Spring Data types. Single source of truth for the JPA adapters.
 */
final class Paging {

    private Paging() {
    }

    static Pageable toPageable(PageQuery query) {
        List<Sort.Order> orders = new ArrayList<>(query.orders().size());
        for (PageQuery.Order order : query.orders()) {
            Sort.Direction direction = order.descending() ? Sort.Direction.DESC : Sort.Direction.ASC;
            orders.add(Sort.Order.by(order.field()).with(direction));
        }
        return PageRequest.of(query.page(), query.size(), Sort.by(orders));
    }

    static <T> PageResult<T> toPageResult(Page<T> page) {
        return new PageResult<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements());
    }
}
