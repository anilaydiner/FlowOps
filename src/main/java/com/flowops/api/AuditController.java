package com.flowops.api;

import com.flowops.domain.AuditEvent;
import com.flowops.security.UserPrincipal;
import com.flowops.application.service.AuditService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/**
 * Audit timeline API (PLAN §8, iteration 8).
 *
 * <p>Read-only, append-only-backed history of one request:
 * status transitions, submission, decisions, execution jobs, SLA events.
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
                    finished), and SLA breach events.

                    **Filters** (all optional):
                    - `from` — ISO-8601 instant, inclusive lower bound
                    - `to`   — ISO-8601 instant, inclusive upper bound
                    - `type` — STATUS | SUBMITTED | DECISION | JOB | SLA

                    Owner or admin only; 404 for other users (no existence leak).
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Audit timeline (oldest first)"),
            @ApiResponse(responseCode = "400", description = "Unknown `type` filter"),
            @ApiResponse(responseCode = "404", description = "Request not found (or not the owner)")
    })
    public List<AuditEvent> audit(
            @Parameter(description = "Request ID") @PathVariable Long id,
            @Parameter(description = "Inclusive lower bound (ISO-8601)") @RequestParam(required = false) Instant from,
            @Parameter(description = "Inclusive upper bound (ISO-8601)") @RequestParam(required = false) Instant to,
            @Parameter(description = "Event type filter") @RequestParam(required = false) String type,
            @AuthenticationPrincipal UserPrincipal caller) {
        return auditService.timeline(id, from, to, type, caller.toIdentity());
    }
}
