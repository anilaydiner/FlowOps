package com.flowops.api;

import com.flowops.api.dto.*;
import com.flowops.api.web.Pagination;
import com.flowops.application.mediator.Mediator;
import com.flowops.application.request.command.RecoverRequestCommand;
import com.flowops.application.request.port.BlockedRequestQueries;
import com.flowops.application.request.recovery.BlockedRequests;
import com.flowops.domain.request.BlockReason;
import com.flowops.domain.request.RecoveryAssignment;
import com.flowops.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/requests")
public class BlockedRequestController {
    private final Mediator mediator;
    private final BlockedRequests blocked;
    public BlockedRequestController(Mediator mediator, BlockedRequests blocked) { this.mediator = mediator; this.blocked = blocked; }

    public record BlockedResponse(RequestResponse request, com.flowops.application.request.model.BlockedRequestView.BlockedStep block) {}

    @GetMapping("/blocked")
    @Operation(summary="Blocked requests visible to the current workflow operator")
    public PageResponse<BlockedResponse> list(@RequestParam(required=false) BlockReason reason,
            @RequestParam(required=false) Instant blockedBefore, @RequestParam(required=false) Long ownerId,
            @RequestParam(required=false) String team, @RequestParam(required=false) List<String> sort,
            @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size,
            @AuthenticationPrincipal UserPrincipal caller) {
        var paging = Pagination.toPageQuery(page, size, sort, List.of("id", "status", "createdAt", "updatedAt"));
        return PageResponse.from(blocked.list(paging, new BlockedRequestQueries.Filter(reason, blockedBefore, ownerId, team), caller.toIdentity())
                .map(row -> new BlockedResponse(RequestResponse.from(row.request()), row.block())));
    }

    @PostMapping("/{id}/recover")
    @Operation(summary="Restore a blocked step without changing its approval threshold or counting a vote")
    public RequestResponse recover(@PathVariable Long id, @Valid @RequestBody RecoverRequest body,
                                   @AuthenticationPrincipal UserPrincipal caller) {
        var assignments = body.assignments() == null ? List.<RecoveryAssignment>of() : body.assignments().stream()
                .map(a -> new RecoveryAssignment(a.predecessorId(), a.userId())).toList();
        return RequestResponse.from(mediator.send(new RecoverRequestCommand(id, body.stepInstanceId(), body.expectedVersion(),
                body.reason(), !Boolean.FALSE.equals(body.refreshPool()), assignments, false, caller.toIdentity())));
    }

    @GetMapping("/{id}/recoveries")
    @Operation(summary="Operator recovery history; requires scoped blocked-request read access")
    public List<com.flowops.domain.request.StepRecovery> history(@PathVariable Long id,
                                                               @AuthenticationPrincipal UserPrincipal caller) {
        return blocked.history(id, caller.toIdentity());
    }

    @PostMapping("/{id}/recheck")
    @Operation(summary="Recheck current eligibility; block unreachable work or resume a restored roster")
    public RequestResponse recheck(@PathVariable Long id, @Valid @RequestBody RecoverRequest body,
                                   @AuthenticationPrincipal UserPrincipal caller) {
        if (body.assignments() != null && !body.assignments().isEmpty()) {
            throw new com.flowops.domain.exception.RequestValidationException(java.util.Map.of("assignments", "Use recover to change participants"));
        }
        return RequestResponse.from(mediator.send(new RecoverRequestCommand(id, body.stepInstanceId(), body.expectedVersion(),
                body.reason(), false, List.of(), true, caller.toIdentity())));
    }
}
