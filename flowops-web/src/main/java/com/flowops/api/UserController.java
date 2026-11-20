package com.flowops.api;

import com.flowops.api.dto.PageResponse;
import com.flowops.api.dto.UserRequest;
import com.flowops.api.dto.UserResponse;
import com.flowops.api.web.Pagination;
import com.flowops.application.identity.UserService;
import com.flowops.domain.identity.query.UserFilter;
import com.flowops.domain.paging.PageQuery;
import com.flowops.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @Operation(summary = "List users (admin only)", description = """
            Supports dynamic filtering, multi-sort, and pagination.

            **Filters** — all optional, combined with AND:
            - `username` — partial match (case-insensitive)
            - `displayName` — partial match
            - `email` — partial match
            - `departmentCode` — exact match (e.g. FINANCE)
            - `roleCode` — exact role code match (e.g. ADMIN)
            - `active` — true / false

            **Sorting** — repeatable `sort` param, format `field,asc|desc`:
            - `sort=createdAt,desc`
            - `sort=createdAt,desc&sort=username,asc`  (multi-sort)

            **Pagination**:
            - `page` — one-based page number (default 1)
            - `size` — page size (default 20, max 100)
            """)
    @Parameters({ @Parameter(name = "username", description = "Partial username match (case-insensitive)"),
            @Parameter(name = "displayName", description = "Partial display-name match"),
            @Parameter(name = "email", description = "Partial email match"),
            @Parameter(name = "departmentCode", description = "Exact department code (e.g. FINANCE)"),
            @Parameter(name = "roleCode", description = "Exact role code (ADMIN or MEMBER)"),
            @Parameter(name = "active", description = "Filter by active status"),
            @Parameter(name = "sort",
                    description = "Sort field + direction: field,asc|desc. Repeatable for multi-sort."),
            @Parameter(name = "page", description = "One-based page number (default 1)"),
            @Parameter(name = "size", description = "Page size (default 20, max 100)") })
    @ApiResponses({ @ApiResponse(responseCode = "200", description = "Paged user list"),
            @ApiResponse(responseCode = "403", description = "Not an admin") })
    public PageResponse<UserResponse> list(@RequestParam(required = false) String username,
            @RequestParam(required = false) String displayName, @RequestParam(required = false) String email,
            @RequestParam(required = false) String departmentCode, @RequestParam(required = false) String roleCode,
            @RequestParam(required = false) Boolean active, @RequestParam(required = false) List<String> sort,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal caller) {
        UserFilter filter = new UserFilter(username, displayName, email, departmentCode, roleCode, active);
        PageQuery pageQuery = Pagination.toPageQuery(page, size, sort, ALLOWED_SORT_FIELDS);
        return PageResponse.from(userService.list(filter, pageQuery, caller.toIdentity()).map(UserResponse::from));
    }

    /** Whitelist of sortable fields — prevents arbitrary column access. */
    static final List<String> ALLOWED_SORT_FIELDS = List.of("id", "username", "displayName", "email", "active",
            "createdAt", "updatedAt");

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal UserPrincipal caller) {
        return UserResponse.from(userService.get(caller.id(), caller.toIdentity()));
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal caller) {
        return UserResponse.from(userService.get(id, caller.toIdentity()));
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody UserRequest request,
            @AuthenticationPrincipal UserPrincipal caller) {
        var user = userService.create(request.username(), request.displayName(), request.email(),
                request.departmentCode(), request.roles(), request.password(), request.active(), caller.toIdentity());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
    }

    @PutMapping("/{id}")
    public UserResponse update(@PathVariable Long id, @RequestBody UserRequest request,
            @AuthenticationPrincipal UserPrincipal caller) {
        var user = userService.update(id, request.displayName(), request.email(), request.departmentCode(),
                request.roles(), request.password(), request.active(), caller.toIdentity());
        return UserResponse.from(user);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal caller) {
        userService.delete(id, caller.toIdentity());
    }

}
