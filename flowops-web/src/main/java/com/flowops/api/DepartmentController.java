package com.flowops.api;

import com.flowops.api.dto.PageResponse;
import com.flowops.api.web.Pagination;
import com.flowops.application.organization.DepartmentService;
import com.flowops.domain.organization.Department;
import com.flowops.domain.organization.query.DepartmentFilter;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/departments")
public class DepartmentController {

    private static final List<String> ALLOWED_SORT_FIELDS = List.of("id", "code", "name", "createdAt");

    private final DepartmentService departments;

    public DepartmentController(DepartmentService departments) {
        this.departments = departments;
    }

    @GetMapping
    @Operation(summary = "List departments", description = """
            Supports dynamic filtering, multi-sort, and pagination.

            **Filters** — all optional, combined with AND:
            - `code` — partial match (case-insensitive)
            - `name` — partial match (case-insensitive)

            **Sorting** — repeatable `sort` param, format `field,asc|desc`:
            - `sort=code,asc`
            - `sort=name,desc&sort=code,asc`  (multi-sort)

            **Pagination**:
            - `page` — one-based page number (default 1)
            - `size` — page size (default 20, max 100)
            """)
    @Parameters({ @Parameter(name = "code", description = "Partial department code match (case-insensitive)"),
            @Parameter(name = "name", description = "Partial department name match (case-insensitive)"),
            @Parameter(name = "sort",
                    description = "Sort field + direction: field,asc|desc. Repeatable for multi-sort."),
            @Parameter(name = "page", description = "One-based page number (default 1)"),
            @Parameter(name = "size", description = "Page size (default 20, max 100)") })
    @ApiResponses({ @ApiResponse(responseCode = "200", description = "Paged department list"),
            @ApiResponse(responseCode = "403", description = "Missing permission") })
    public PageResponse<Department> list(@RequestParam(required = false) String code,
            @RequestParam(required = false) String name, @RequestParam(required = false) List<String> sort,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal caller) {
        DepartmentFilter filter = new DepartmentFilter(code, name);
        PageQuery pageQuery = Pagination.toPageQuery(page, size, sort, ALLOWED_SORT_FIELDS);
        PageResult<Department> result = departments.list(filter, pageQuery, caller.toIdentity());
        return PageResponse.from(result);
    }

}
