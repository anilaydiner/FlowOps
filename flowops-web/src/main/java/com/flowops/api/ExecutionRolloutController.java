package com.flowops.api;

import com.flowops.application.execution.ExecutionRolloutChecks;
import com.flowops.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/execution/rollout")
public class ExecutionRolloutController {

    private final ExecutionRolloutChecks checks;

    public ExecutionRolloutController(ExecutionRolloutChecks checks) {
        this.checks = checks;
    }

    @GetMapping
    @Operation(
            summary = "Read-only execution rollout checks; requires active global execution:rollout:read. Does not certify manual acceptance.")
    public ExecutionRolloutChecks.Report check(
            @RequestParam(defaultValue = "PRE_ENABLE") ExecutionRolloutChecks.Phase phase,
            @AuthenticationPrincipal UserPrincipal caller) {
        return checks.check(phase, caller.toIdentity());
    }

}
