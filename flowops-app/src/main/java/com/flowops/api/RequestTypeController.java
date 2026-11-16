package com.flowops.api;

import com.flowops.api.dto.PageResponse;
import com.flowops.domain.query.RequestTypeFilter;
import com.flowops.api.dto.RequestTypeRequest;
import com.flowops.domain.RequestTypeSpec;
import com.flowops.api.dto.RequestTypeResponse;
import com.flowops.application.workflow.RequestTypeService;
import com.flowops.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import com.flowops.domain.paging.PageQuery;
import com.flowops.api.web.Pagination;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/request-types")
public class RequestTypeController {

    private static final List<String> ALLOWED_SORT_FIELDS = List.of(
            "id", "code", "name", "createdAt", "updatedAt");

    private final RequestTypeService requestTypeService;

    public RequestTypeController(RequestTypeService requestTypeService) {
        this.requestTypeService = requestTypeService;
    }

    @GetMapping
    @Operation(summary = "List request types",
            description = """
                    Supports dynamic filtering, multi-sort, and pagination.

                    **Filters** — all optional, combined with AND:
                    - `code` — partial match (case-insensitive)
                    - `name` — partial match
                    - `departmentCode` — exact owning-department code (e.g. FINANCE)
                    - `active` — true / false

                    **Sorting** — repeatable `sort` param, format `field,asc|desc`
                    **Pagination**: `page` (default 1), `size` (default 20, max 100)
                    """)
    @Parameters({
            @Parameter(name = "code", description = "Partial type code match (case-insensitive)"),
            @Parameter(name = "name", description = "Partial type name match"),
            @Parameter(name = "departmentCode", description = "Exact owning department code (e.g. FINANCE)"),
            @Parameter(name = "active", description = "Filter by active flag"),
            @Parameter(name = "sort", description = "Sort field + direction: field,asc|desc. Repeatable for multi-sort."),
            @Parameter(name = "page", description = "One-based page number (default 1)"),
            @Parameter(name = "size", description = "Page size (default 20, max 100)")
    })
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paged request-type list"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Missing permission")
    })
    public PageResponse<RequestTypeResponse> list(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String departmentCode,
            @RequestParam(required = false) Boolean active,
            @RequestParam(required = false) List<String> sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal caller
    ) {
        RequestTypeFilter filter = new RequestTypeFilter(code, name, departmentCode, active);
        PageQuery pageQuery = Pagination.toPageQuery(page, size, sort, ALLOWED_SORT_FIELDS);
        return PageResponse.from(requestTypeService.list(filter, pageQuery, caller.toIdentity()).map(RequestTypeResponse::from));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get request type by ID")
    public RequestTypeResponse get(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal caller) {
        return RequestTypeResponse.from(requestTypeService.get(id, caller.toIdentity()));
    }

    @PostMapping
    @Operation(summary = "Create request type")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Request type created"),
            @ApiResponse(responseCode = "403", description = "Missing permission"),
            @ApiResponse(responseCode = "409", description = "Code already exists")
    })
    public ResponseEntity<RequestTypeResponse> create(@Valid @RequestBody RequestTypeRequest request,
                                                       @AuthenticationPrincipal UserPrincipal caller) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(RequestTypeResponse.from(requestTypeService.create(toSpec(request), caller.toIdentity())));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update request type")
    public RequestTypeResponse update(@PathVariable Long id, @RequestBody RequestTypeRequest request,
                                      @AuthenticationPrincipal UserPrincipal caller) {
        return RequestTypeResponse.from(requestTypeService.update(id, toSpec(request), caller.toIdentity()));
    }

    private RequestTypeSpec toSpec(RequestTypeRequest r) {
        return new RequestTypeSpec(
                r.code(), r.name(), r.description(), r.departmentCode(),
                r.workflowCode(), r.requiredFields(), r.validationRules());
    }
}
