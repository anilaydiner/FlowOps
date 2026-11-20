package com.flowops.api;

import com.flowops.application.audit.AuditService;
import com.flowops.domain.audit.AuditEvent;
import com.flowops.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.time.Instant;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Audit timeline API (PLAN §8, iteration 8).
 *
 * <p>
 * Read-only, append-only-backed history of one request: status transitions, submission,
 * decisions, execution jobs, SLA events.
 */
@RestController
@RequestMapping("/api/v1/requests")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    @GetMapping("/{id}/audit")
    @Operation(summary = "Audit timeline for a request",
            description = """
                    Chronological, append-only history: STATUS transitions,
                    SUBMITTED, DECISION (with actor + evidence), JOB (created +
                    attempts), STEP, PARTICIPANT, CLARIFICATION, REQUEST edits, APPROVAL,
                    EXECUTION, EXECUTION_PLAN recovery/attention and SLA events.
                    Events include stable ids, sequence numbers and provenance. Recorded history
                    is read from immutable storage; legacy reconstructions/baselines are labeled.

                    **Filters** (all optional):
                    - `from` — ISO-8601 instant, inclusive lower bound
                    - `to`   — ISO-8601 instant, inclusive upper bound
                    - `type` — STATUS | SUBMITTED | DECISION | JOB | SLA | STEP | PARTICIPANT | CLARIFICATION | REQUEST | APPROVAL | EXECUTION | EXECUTION_PLAN

                    Request owners require audit:read; other callers require audit:read:any.
                    Approval participation alone does not grant audit access.
                    """)
    @ApiResponses({ @ApiResponse(responseCode = "200", description = "Audit timeline (oldest first)"),
            @ApiResponse(responseCode = "400", description = "Unknown `type` filter"),
            @ApiResponse(responseCode = "403", description = "Audit permission not granted"),
            @ApiResponse(responseCode = "404", description = "Request not found") })
    public List<AuditEvent> audit(@Parameter(description = "Request ID") @PathVariable Long id,
            @Parameter(description = "Inclusive lower bound (ISO-8601)") @RequestParam(required = false) Instant from,
            @Parameter(description = "Inclusive upper bound (ISO-8601)") @RequestParam(required = false) Instant to,
            @Parameter(description = "Event type filter") @RequestParam(required = false) String type,
            @AuthenticationPrincipal UserPrincipal caller) {
        return auditService.timeline(id, from, to, type, caller.toIdentity());
    }

}
