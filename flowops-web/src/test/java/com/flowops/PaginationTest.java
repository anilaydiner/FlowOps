package com.flowops;

import com.flowops.api.dto.PageResponse;
import com.flowops.api.web.Pagination;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaginationTest {
    @Test
    void convertsOffsetsAndPreservesWhitelistedMultiSortAndSizeLimits() {
        var query = Pagination.toPageQuery(2, 500,
                List.of("createdAt,desc", "ID,asc", "passwordHash,desc"), List.of("createdAt", "id"));
        assertEquals(1, query.page());
        assertEquals(100, query.size());
        assertEquals(List.of(new PageQuery.Order("createdAt", true), new PageQuery.Order("id", false)), query.orders());
        assertEquals(1, Pagination.toPageQuery(1, 0, null, List.of()).size());
    }

    @Test
    void responseFlagsAndPagesMatchOneBasedNumbersIncludingEmptyAndOutOfRangePages() {
        var first = PageResponse.from(new PageResult<>(List.of("first"), 0, 1, 2));
        assertEquals(1, first.page());
        assertTrue(first.first());
        assertFalse(first.last());
        var last = PageResponse.from(new PageResult<>(List.of("last"), 1, 1, 2));
        assertEquals(2, last.page());
        assertFalse(last.first());
        assertTrue(last.last());
        var empty = PageResponse.from(PageResult.empty(0, 20));
        assertEquals(1, empty.page());
        assertTrue(empty.first());
        assertTrue(empty.last());
        assertEquals(0, empty.totalPages());
        var beyond = PageResponse.from(new PageResult<>(List.of(), 4, 1, 2));
        assertEquals(5, beyond.page());
        assertFalse(beyond.first());
        assertTrue(beyond.last());
    }
}
