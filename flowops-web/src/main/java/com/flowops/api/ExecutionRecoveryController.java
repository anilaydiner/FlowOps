package com.flowops.api;

import com.flowops.application.execution.*;
import com.flowops.application.execution.ExecutionRecoveryTransactions.Input;
import com.flowops.application.execution.ExecutionRecoveryHistory.Receipt;
import com.flowops.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/requests/{requestId}/execution-recovery")
public class ExecutionRecoveryController {
    public record Command(@NotNull UUID commandId,@NotNull @PositiveOrZero Long expectedVersion,
                          @NotBlank @Size(max=1000) String reason) {
        Input input() { return new Input(commandId,expectedVersion,reason); }
    }
    public record Response(Long requestVersion,Receipt command) {
        static Response from(ExecutionRecoveryTransactions.Result result) {
            return new Response(result.requestVersion(),Receipt.from(result.entry()));
        }
    }
    private final RecoverExecution recovery;
    private final ExecutionRecoveryHistory history;
    private final ExecutionAttemptHistory attempts;
    public ExecutionRecoveryController(RecoverExecution recovery,ExecutionRecoveryHistory history,ExecutionAttemptHistory attempts) {
        this.recovery=recovery;this.history=history;this.attempts=attempts;
    }
    @GetMapping
    @Operation(summary="Execution attention and bounded immutable recovery history; requires scoped execution:read")
    public ExecutionRecoveryHistory.View history(@PathVariable Long requestId,@RequestParam(defaultValue="50") int limit,
                                                 @AuthenticationPrincipal UserPrincipal caller) {
        return history.get(requestId,limit,caller.toIdentity());
    }
    @GetMapping("/{jobId}/attempts")
    @Operation(summary="Immutable attempt starts and labeled legacy baseline; requires scoped execution:read")
    public ExecutionAttemptHistory.Attempts attempts(@PathVariable Long requestId,@PathVariable Long jobId,
            @RequestParam(required=false) Integer beforeAttempt,@RequestParam(defaultValue="50") int limit,
            @AuthenticationPrincipal UserPrincipal caller) {
        return attempts.attempts(requestId,jobId,beforeAttempt,limit,caller.toIdentity());
    }
    @GetMapping("/{jobId}/attempt-events")
    @Operation(summary="Immutable attempt outcomes, heartbeats and recovery facts; requires scoped execution:read")
    public java.util.List<ExecutionAttemptHistory.Event> events(@PathVariable Long requestId,@PathVariable Long jobId,
            @RequestParam(required=false) Long beforeSequence,@RequestParam(defaultValue="50") int limit,
            @AuthenticationPrincipal UserPrincipal caller) {
        return attempts.events(requestId,jobId,beforeSequence,limit,caller.toIdentity());
    }
    @PostMapping("/{jobId}/reconcile")
    @Operation(summary="Query provider outcome without executing; requires scoped execution:reconcile")
    public Response reconcile(@PathVariable Long requestId,@PathVariable Long jobId,@Valid @RequestBody Command body,
                              @AuthenticationPrincipal UserPrincipal caller) {
        return Response.from(recovery.reconcile(requestId,jobId,body.input(),caller.toIdentity()));
    }
    @PostMapping("/{jobId}/retry")
    @Operation(summary="Authorize one safe retry of held work with the original key/input; requires scoped execution:retry")
    public Response retry(@PathVariable Long requestId,@PathVariable Long jobId,@Valid @RequestBody Command body,
                          @AuthenticationPrincipal UserPrincipal caller) {
        return Response.from(recovery.retry(requestId,jobId,body.input(),caller.toIdentity()));
    }
}
