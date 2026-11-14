package com.flowops.api;

import com.flowops.api.dto.PageResponse;
import com.flowops.domain.query.UserFilter;
import com.flowops.api.dto.UserRequest;
import com.flowops.api.dto.UserResponse;
import com.flowops.security.UserPrincipal;
import com.flowops.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @Operation(
            summary = "List users (admin only)",
            description = """
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
                    - `page` — zero-based page number (default 0)
                    - `size` — page size (default 20, max 100)
                    """)
    @Parameters({
            @Parameter(name = "username", description = "Partial username match (case-insensitive)"),
            @Parameter(name = "displayName", description = "Partial display-name match"),
            @Parameter(name = "email", description = "Partial email match"),
            @Parameter(name = "departmentCode", description = "Exact department code (e.g. FINANCE)"),
            @Parameter(name = "roleCode", description = "Exact role code (ADMIN or MEMBER)"),
            @Parameter(name = "active", description = "Filter by active status"),
            @Parameter(name = "sort", description = "Sort field + direction: field,asc|desc. Repeatable for multi-sort."),
            @Parameter(name = "page", description = "Zero-based page number (default 0)"),
            @Parameter(name = "size", description = "Page size (default 20, max 100)")
    })
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paged user list"),
            @ApiResponse(responseCode = "403", description = "Not an admin")
    })
    public PageResponse<UserResponse> list(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String displayName,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String departmentCode,
            @RequestParam(required = false) String roleCode,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) List<String> sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        UserFilter filter = new UserFilter(username, displayName, email, departmentCode, roleCode, active);
        PageRequest pageRequest = buildPageRequest(page, size, sort, ALLOWED_SORT_FIELDS);
        return PageResponse.from(userService.list(filter, pageRequest).map(UserResponse::from));
    }

    /**
     * Build a {@link PageRequest} from explicit page/size/sort params.
     * Sort format: {@code field,asc} or {@code field,desc}.
     * Unknown fields are silently ignored (prevents SQL injection via arbitrary column names).
     */
    static PageRequest buildPageRequest(int page, int size, List<String> sortParams, List<String> allowedFields) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), 100);

        Sort sort;
        if (sortParams == null || sortParams.isEmpty()) {
            sort = Sort.by(Sort.Direction.ASC, "id");
        } else {
            List<Sort.Order> orders = new ArrayList<>();
            for (String s : sortParams) {
                if (s == null || s.isBlank()) continue;
                String[] parts = s.split(",", 2);
                String field = parts[0].strip();
                if (field.isEmpty()) continue;
                if (!allowedFields.contains(field.toLowerCase())) continue;
                Sort.Direction dir = (parts.length > 1 && parts[1].strip().equalsIgnoreCase("desc"))
                        ? Sort.Direction.DESC
                        : Sort.Direction.ASC;
                orders.add(new Sort.Order(dir, field));
            }
            sort = orders.isEmpty() ? Sort.by(Sort.Direction.ASC, "id") : Sort.by(orders);
        }
        return PageRequest.of(safePage, safeSize, sort);
    }

    /** Whitelist of sortable fields — prevents arbitrary column access. */
    static final List<String> ALLOWED_SORT_FIELDS = List.of(
            "id", "username", "displayName", "email", "active", "createdAt", "updatedAt");

    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal UserPrincipal caller) {
        return UserResponse.from(userService.get(caller.id(), caller));
    }

    @GetMapping("/{id}")
    public UserResponse get(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal caller) {
        return UserResponse.from(userService.get(id, caller));
    }

    @PostMapping
    public ResponseEntity<UserResponse> create(@Valid @RequestBody UserRequest request) {
        var user = userService.create(
                request.username(),
                request.displayName(),
                request.email(),
                request.departmentCode(),
                request.roles(),
                request.password());
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(user));
    }

    @PutMapping("/{id}")
    public UserResponse update(@PathVariable Long id, @RequestBody UserRequest request) {
        var user = userService.update(
                id,
                request.displayName(),
                request.email(),
                request.departmentCode(),
                request.roles(),
                request.password());
        return UserResponse.from(user);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal caller) {
        userService.delete(id, caller);
    }
}
