package com.flowops.api;

import com.flowops.api.dto.CreateRequestRequest;
import com.flowops.api.dto.DecisionRequest;
import com.flowops.api.dto.EligibleDelegateeResponse;
import com.flowops.api.dto.PageResponse;
import com.flowops.api.dto.RequestResponse;
import com.flowops.api.dto.ParticipantResponse;
import com.flowops.application.request.query.GetRequestParticipants;
import com.flowops.api.dto.UpdateRequestRequest;
import com.flowops.api.web.Pagination;
import com.flowops.application.mediator.Mediator;
import com.flowops.application.request.command.CancelRequestCommand;
import com.flowops.application.request.command.CloseRequestCommand;
import com.flowops.application.request.command.CreateDraftCommand;
import com.flowops.application.request.command.DecideStepCommand;
import com.flowops.application.request.command.SubmitRequestCommand;
import com.flowops.application.request.command.UpdateDraftCommand;
import com.flowops.application.request.query.GetApprovalQueue;
import com.flowops.application.request.query.GetEligibleDelegatees;
import com.flowops.application.request.query.GetRequest;
import com.flowops.application.request.query.ListRequests;
import com.flowops.domain.paging.PageQuery;
import com.flowops.domain.request.query.RequestFilter;
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
@RequestMapping("/api/v1/requests")
public class RequestController {

    private static final List<String> ALLOWED_SORT_FIELDS = List.of(
            "id", "status", "createdAt", "updatedAt");

    private final Mediator mediator;

    public RequestController(Mediator mediator) {
        this.mediator = mediator;
    }

    @PostMapping
    @Operation(summary = "Create a draft request",
            description = """
                    Creates a request in DRAFT state, validated against the type's
                    required fields and rules. Example payload for `IT_ACCESS`:
                    `{"resource": "Jira", "duration": "30d"}`

                    Validation failures return 400 with per-field `violations`.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Draft created"),
            @ApiResponse(responseCode = "400", description = "Payload failed per-type validation (violations included)"),
            @ApiResponse(responseCode = "404", description = "Request type not found")
    })
    public ResponseEntity<RequestResponse> createDraft(@Valid @RequestBody CreateRequestRequest request,
                                                       @AuthenticationPrincipal UserPrincipal caller) {
        RequestResponse body = RequestResponse.from(
                mediator.send(new CreateDraftCommand(request.typeCode(), request.payload(), caller.toIdentity())));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping
    @Operation(summary = "List requests",
            description = """
                    Admins see all requests; other users see only their own.

                    **Filters** — all optional, combined with AND:
                    - `status` — exact match (DRAFT, IN_REVIEW, …)
                    - `typeCode` — exact type code (e.g. EXPENSE)
                    - `mine` — true to restrict to the caller's requests (admin only)

                    Responses retain business outcome and lifecycle milestone times after closure.
                    executionState, executionProgress and requiresAttention summarize current
                    frozen-plan/job evidence. Unknown expected work has expectedJobs=null;
                    only an explicitly empty plan has expectedJobs=0.

                    **Sorting** — repeatable `sort` param, format `field,asc|desc`
                    **Pagination**: `page` (default 1), `size` (default 20, max 100)
                    """)
    @Parameters({
            @Parameter(name = "status", description = "Exact status (e.g. DRAFT)"),
            @Parameter(name = "typeCode", description = "Exact request-type code (e.g. EXPENSE)"),
            @Parameter(name = "mine", description = "Restrict to the caller's requests (admin only)"),
            @Parameter(name = "sort", description = "Sort field + direction: field,asc|desc. Repeatable for multi-sort."),
            @Parameter(name = "page", description = "One-based page number (default 1)"),
            @Parameter(name = "size", description = "Page size (default 20, max 100)")
    })
    public PageResponse<RequestResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String typeCode,
            @RequestParam(required = false) Boolean mine,
            @RequestParam(required = false) List<String> sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal caller
    ) {
        RequestFilter filter = new RequestFilter(status, typeCode, mine);
        PageQuery pageQuery = Pagination.toPageQuery(page, size, sort, ALLOWED_SORT_FIELDS);
        return PageResponse.from(mediator.send(new ListRequests(filter, pageQuery, caller.toIdentity())).map(RequestResponse::from));
    }

    @GetMapping("/queue")
    @Operation(summary = "My pending approvals (approver inbox)",
            description = """
                    Returns IN_REVIEW requests where the caller is eligible for the
                    current PENDING step using current account state, workflow participation,
                    permission, scope and separation of duties. Prior voters and users who
                    transferred responsibility are excluded; access-token role claims are not authoritative.

                    **Sorting** — repeatable `sort` param, format `field,asc|desc`
                    **Pagination**: `page` (default 1), `size` (default 20, max 100)
                    """)
    @Parameters({
            @Parameter(name = "sort", description = "Sort field + direction: field,asc|desc. Repeatable for multi-sort."),
            @Parameter(name = "page", description = "One-based page number (default 1)"),
            @Parameter(name = "size", description = "Page size (default 20, max 100)")
    })
    public PageResponse<RequestResponse> queue(
            @RequestParam(required = false) List<String> sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal UserPrincipal caller
    ) {
        PageQuery pageQuery = Pagination.toPageQuery(page, size, sort, ALLOWED_SORT_FIELDS);
        return PageResponse.from(mediator.send(new GetApprovalQueue(pageQuery, caller.toIdentity())).map(RequestResponse::from));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get request detail",
            description = "Uses normal persisted request read/ownership and current participant access rules. Includes retained outcome/milestones and live execution progress/attention without provider calls.")
    public RequestResponse get(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal caller) {
        return RequestResponse.from(mediator.send(new GetRequest(id, caller.toIdentity())));
    }

    @GetMapping("/{id}/execution-plan")
    @Operation(summary="Read the frozen approval-time action plan using normal request read access; known=false means legacy or not yet approved")
    public com.flowops.application.request.query.GetExecutionPlan.View executionPlan(@PathVariable Long id,@AuthenticationPrincipal UserPrincipal caller) {
        return mediator.send(new com.flowops.application.request.query.GetExecutionPlan(id,caller.toIdentity()));
    }

    @GetMapping("/{id}/participants")
    @Operation(summary = "Approval roster and delegation history", description = "Uses the same access rules as request detail. Legacy steps have no fabricated roster.")
    public List<ParticipantResponse> participants(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal caller) {
        return ParticipantResponse.from(mediator.send(new GetRequestParticipants(id, caller.toIdentity())));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Edit draft payload (owner only)",
            description = """
                    Only DRAFT requests can be edited; re-validates the payload
                    against the type's rules. Non-draft states return 409.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Draft updated"),
            @ApiResponse(responseCode = "400", description = "Payload failed per-type validation"),
            @ApiResponse(responseCode = "403", description = "Not the owner"),
            @ApiResponse(responseCode = "409", description = "Request is not in DRAFT state")
    })
    public RequestResponse updateDraft(@PathVariable Long id,
                                       @Valid @RequestBody UpdateRequestRequest request,
                                       @AuthenticationPrincipal UserPrincipal caller) {
        return RequestResponse.from(mediator.send(new UpdateDraftCommand(id, request.payload(), caller.toIdentity())));
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancel request before durable execution start",
            description = "Owner/scoped cancellation permissions apply. Cancels active steps and unstarted jobs atomically; "
                    + "a durable execution start or unknown execution history returns 409. Authorized cancellation replay is idempotent.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Request cancelled"),
            @ApiResponse(responseCode = "409", description = "Execution started, history/plan is unknown, or request cannot be cancelled")
    })
    public RequestResponse cancel(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal caller) {
        return RequestResponse.from(mediator.send(new CancelRequestCommand(id, caller.toIdentity())));
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "Submit a draft into the workflow (owner or admin)",
            description = """
                    DRAFT → IN_REVIEW. The type's default workflow is materialized:
                    the first step whose condition holds becomes the current
                    (PENDING or BLOCKED when no eligible approver exists); steps
                    whose condition does not hold are recorded as SKIPPED.
                    If every step is skipped the request becomes APPROVED.
                    Non-draft states return 409.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Submitted (IN_REVIEW or APPROVED)"),
            @ApiResponse(responseCode = "404", description = "Request not found (or not the owner)"),
            @ApiResponse(responseCode = "409", description = "Not in DRAFT, no workflow configured, or workflow inactive")
    })
    public RequestResponse submit(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal caller) {
        return RequestResponse.from(mediator.send(new SubmitRequestCommand(id, caller.toIdentity())));
    }

    @PostMapping("/{id}/decisions")
    @Operation(summary = "Record an APPROVE or REJECT decision on the current step",
            description = """
                    Separation of duties is enforced: the requester cannot decide
                    their own request (403). The caller must hold the step's
                    required role. A REJECT ends the workflow (REJECTED); an
                    APPROVE satisfying the step's completion rule advances to the
                    next applicable step, or APPROVED when none remain.
                    Concurrent decisions serialize via optimistic locking (409).
                    A person may approve a step once. Delegation transfers responsibility.
                    Supply stepInstanceId and expectedVersion from the latest detail response
                    to reject stale submissions; both are optional for existing v1 clients.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Decision recorded"),
            @ApiResponse(responseCode = "403", description = "SoD violation or missing required role"),
            @ApiResponse(responseCode = "404", description = "Request not found"),
            @ApiResponse(responseCode = "409", description = "Not in review, no active step, or optimistic-lock conflict")
    })
    public RequestResponse decide(@PathVariable Long id,
                                  @Valid @RequestBody com.flowops.api.dto.DecisionRequest body,
                                  @AuthenticationPrincipal UserPrincipal caller) {
        return RequestResponse.from(mediator.send(new DecideStepCommand(id, body.action(), body.justification(), body.delegatedTo(), caller.toIdentity(), body.stepInstanceId(), body.expectedVersion())));
    }

    @GetMapping("/{id}/eligible-delegatees")
    @Operation(summary = "List users eligible to receive a delegation of this decision",
            description = """
                    Only the current approver may call this (403 otherwise). Returns active
                    users who would pass the same approval gate — i.e. who can actually act on
                    this request. The caller and the requester are excluded. Scoped permissions
                    (type / department / threshold) are honoured, so a user whose scope does not
                    match this request is not listed.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Eligible delegatees (possibly empty)"),
            @ApiResponse(responseCode = "403", description = "Caller is not the current approver"),
            @ApiResponse(responseCode = "404", description = "Request not found"),
            @ApiResponse(responseCode = "409", description = "Request is not in review or has no active step")
    })
    public List<EligibleDelegateeResponse> eligibleDelegatees(
            @PathVariable Long id, @AuthenticationPrincipal UserPrincipal caller) {
        return mediator.send(new GetEligibleDelegatees(id, caller.toIdentity())).stream()
                .map(EligibleDelegateeResponse::from)
                .toList();
    }

    @PostMapping("/{id}/close")
    @Operation(summary = "Close an EXECUTED or REJECTED request (requester or admin)",
            description = "Transitions to CLOSED while retaining the EXECUTED/REJECTED outcome and original milestones, and recording closedAt. Other states, including an already closed request, return 409.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Request closed"),
            @ApiResponse(responseCode = "409", description = "Request is not in EXECUTED or REJECTED")
    })
    public RequestResponse close(@PathVariable Long id, @AuthenticationPrincipal UserPrincipal caller) {
        return RequestResponse.from(mediator.send(new CloseRequestCommand(id, caller.toIdentity())));
    }
}
