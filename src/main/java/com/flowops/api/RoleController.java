package com.flowops.api;

import com.flowops.api.dto.PageResponse;
import com.flowops.domain.query.RoleFilter;
import com.flowops.api.dto.RoleRequest;
import com.flowops.domain.RoleSpec;
import com.flowops.api.dto.RoleResponse;
import com.flowops.application.service.RoleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import com.flowops.domain.paging.PageQuery;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/roles")
public class RoleController {

    private static final List<String> ALLOWED_SORT_FIELDS = List.of(
            "id", "code", "name", "createdAt");

    private final RoleService roleService;

    public RoleController(RoleService roleService) {
        this.roleService = roleService;
    }

    @GetMapping
    @Operation(summary = "List roles",
            description = """
                    Supports dynamic filtering, multi-sort, and pagination.

                    **Filters** — all optional, combined with AND:
                    - `code` — partial match (case-insensitive)
                    - `name` — partial match

                    **Sorting** — repeatable `sort` param, format `field,asc|desc`
                    **Pagination**: `page` (default 0), `size` (default 20, max 100)
                    """)
    @Parameters({
            @Parameter(name = "code", description = "Partial role code match (case-insensitive)"),
            @Parameter(name = "name", description = "Partial role name match"),
            @Parameter(name = "sort", description = "Sort field + direction: field,asc|desc. Repeatable for multi-sort."),
            @Parameter(name = "page", description = "Zero-based page number (default 0)"),
            @Parameter(name = "size", description = "Page size (default 20, max 100)")
    })
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paged role list"),
            @ApiResponse(responseCode = "401", description = "Not authenticated")
    })
    public PageResponse<RoleResponse> list(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) List<String> sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        RoleFilter filter = new RoleFilter(code, name);
        PageQuery pageQuery = UserController.buildPageQuery(page, size, sort, ALLOWED_SORT_FIELDS);
        return PageResponse.from(roleService.list(filter, pageQuery).map(RoleResponse::from));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get role by ID")
    public RoleResponse get(@PathVariable Long id) {
        return RoleResponse.from(roleService.get(id));
    }

    @PostMapping
    @Operation(summary = "Create role (admin only)")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Role created"),
            @ApiResponse(responseCode = "409", description = "Role code already exists")
    })
    public ResponseEntity<RoleResponse> create(@Valid @RequestBody RoleRequest request) {
        RoleResponse response = RoleResponse.from(roleService.create(toSpec(request)));
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update role (admin only)")
    public RoleResponse update(@PathVariable Long id, @RequestBody RoleRequest request) {
        return RoleResponse.from(roleService.update(id, toSpec(request)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete role (admin only)",
            description = "Built-in roles (ADMIN, MEMBER) cannot be deleted.")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "Role deleted"),
            @ApiResponse(responseCode = "409", description = "Cannot delete built-in role")
    })
    public void delete(@PathVariable Long id) {
        roleService.delete(id);
    }

    private RoleSpec toSpec(RoleRequest r) {
        return new RoleSpec(r.code(), r.name(), r.description(), r.permissions());
    }
}
