package com.flowops.api;

import com.flowops.api.dto.PageResponse;
import com.flowops.api.dto.PermissionResponse;
import com.flowops.api.web.Pagination;
import com.flowops.application.organization.PermissionService;
import com.flowops.domain.identity.query.PermissionFilter;
import com.flowops.domain.paging.PageQuery;
import com.flowops.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/permissions")
public class PermissionController {

    private static final List<String> ALLOWED_SORT_FIELDS = List.of(
            "id", "code", "name", "createdAt");

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @GetMapping
    @Operation(summary = "List permissions",
            description = """
                    Supports dynamic filtering, multi-sort, and pagination.

                    **Filters** — all optional, combined with AND:
                    - `code` — partial match (case-insensitive)
                    - `name` — partial match

                    **Sorting** — repeatable `sort` param, format `field,asc|desc`
                    **Pagination**: `page` (default 1), `size` (default 20, max 100)
                    """)
    @Parameters({
            @Parameter(name = "code", description = "Partial permission code match (case-insensitive)"),
            @Parameter(name = "name", description = "Partial permission name match"),
            @Parameter(name = "sort", description = "Sort field + direction: field,asc|desc. Repeatable for multi-sort."),
            @Parameter(name = "page", description = "One-based page number (default 1)"),
            @Parameter(name = "size", description = "Page size (default 20, max 100)")
    })
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paged permission list"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Missing permission")
    })
    public PageResponse<PermissionResponse> list(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) List<String> sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal caller
    ) {
        PermissionFilter filter = new PermissionFilter(code, name);
        PageQuery pageQuery = Pagination.toPageQuery(page, size, sort, ALLOWED_SORT_FIELDS);
        return PageResponse.from(permissionService.list(filter, pageQuery, caller.toIdentity()).map(PermissionResponse::from));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get permission by ID")
    public PermissionResponse get(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal caller) {
        return PermissionResponse.from(permissionService.get(id, caller.toIdentity()));
    }
}
