package com.flowops.api.web;

import com.flowops.domain.paging.PageQuery;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Converts one-based HTTP page numbers to zero-based application offsets. */
public final class Pagination {

    private Pagination() {
    }

    public static PageQuery toPageQuery(int page, int size, List<String> sortParams, List<String> allowedFields) {
        if (page < 1)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be at least 1");
        int safeSize = Math.min(Math.max(1, size), 100);
        List<PageQuery.Order> orders = new ArrayList<>();
        if (sortParams != null) {
            for (String sort : sortParams) {
                if (sort == null || sort.isBlank())
                    continue;
                String[] parts = sort.split(",", 2);
                String candidate = parts[0].strip();
                String field = allowedFields.stream()
                    .filter(name -> name.equalsIgnoreCase(candidate))
                    .findFirst()
                    .orElse(null);
                if (field == null)
                    continue;
                boolean descending = parts.length > 1 && parts[1].strip().equalsIgnoreCase("desc");
                orders.add(new PageQuery.Order(field, descending));
            }
        }
        return new PageQuery(page - 1, safeSize, orders);
    }

}
