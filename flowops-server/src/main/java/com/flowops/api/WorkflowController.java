package com.flowops.api;

import com.flowops.api.dto.PageResponse;
import com.flowops.api.dto.WorkflowRequest;
import com.flowops.api.dto.WorkflowResponse;
import com.flowops.api.web.Pagination;
import com.flowops.application.workflow.WorkflowService;
import com.flowops.application.workflow.model.WorkflowView;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.paging.PageResult;
import com.flowops.domain.workflow.StepSpec;
import com.flowops.domain.workflow.query.WorkflowFilter;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workflows")
public class WorkflowController {

    private static final List<String> ALLOWED_SORT_FIELDS = List.of(
            "id", "code", "name", "createdAt", "updatedAt");

    private final WorkflowService workflowService;

    public WorkflowController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @GetMapping
    @Operation(summary = "List workflows",
            description = """
                    Supports dynamic filtering, multi-sort, and pagination.

                    **Filters** — all optional, combined with AND:
                    - `code` — partial match (case-insensitive)
                    - `name` — partial match

                    **Sorting** — repeatable `sort` param, format `field,asc|desc`
                    **Pagination**: `page` (default 1), `size` (default 20, max 100)
                    """)
    @Parameters({
            @Parameter(name = "code", description = "Partial workflow code match (case-insensitive)"),
            @Parameter(name = "name", description = "Partial workflow name match"),
            @Parameter(name = "sort", description = "Sort field + direction: field,asc|desc. Repeatable for multi-sort."),
            @Parameter(name = "page", description = "One-based page number (default 1)"),
            @Parameter(name = "size", description = "Page size (default 20, max 100)")
    })
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paged workflow list (with steps)"),
            @ApiResponse(responseCode = "401", description = "Not authenticated"),
            @ApiResponse(responseCode = "403", description = "Missing permission")
    })
    public PageResponse<WorkflowResponse> list(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) List<String> sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal caller
    ) {
        WorkflowFilter filter = new WorkflowFilter(code, name);
        PageQuery pageQuery = Pagination.toPageQuery(page, size, sort, ALLOWED_SORT_FIELDS);
        PageResult<WorkflowResponse> mapped = workflowService.list(filter, pageQuery, caller.toIdentity())
                .map(v -> WorkflowResponse.from(v.workflow(), v.steps()));
        return PageResponse.from(mapped);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get workflow by ID (with ordered steps)")
    public WorkflowResponse get(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal caller) {
        WorkflowView view = workflowService.get(id, caller.toIdentity());
        return WorkflowResponse.from(view.workflow(), view.steps());
    }

    @PostMapping
    @Operation(summary = "Create workflow")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Workflow created"),
            @ApiResponse(responseCode = "403", description = "Missing permission"),
            @ApiResponse(responseCode = "409", description = "Code already exists")
    })
    public ResponseEntity<WorkflowResponse> create(@Valid @RequestBody WorkflowRequest request,
                                                    @AuthenticationPrincipal UserPrincipal caller) {
        WorkflowView view = workflowService.create(
                request.code(), request.name(), request.description(), request.active(), toSpecs(request),
                caller.toIdentity());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(view));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update workflow and its steps")
    public WorkflowResponse update(@PathVariable Long id, @Valid @RequestBody WorkflowRequest request,
                                   @AuthenticationPrincipal UserPrincipal caller) {
        WorkflowView view = workflowService.update(
                id, request.name(), request.description(), request.active(), toSpecs(request),
                caller.toIdentity());
        return toResponse(view);
    }

    // ── DTO ⇄ domain mapping (presentation concern, kept in the API layer) ──

    private List<StepSpec> toSpecs(WorkflowRequest request) {
        return request.steps().stream()
                .map(d -> new StepSpec(
                        d.position(), d.code(), d.name(), d.requiredRole(),
                        d.completionRule(), d.requiredApprovals(), d.condition(),
                        d.slaMinutes(), d.escalationRole(), d.terminal()))
                .toList();
    }

    private WorkflowResponse toResponse(WorkflowView view) {
        return WorkflowResponse.from(view.workflow(), view.steps());
    }
}
