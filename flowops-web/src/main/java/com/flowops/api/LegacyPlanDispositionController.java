package com.flowops.api;

import com.flowops.api.dto.LegacyPlanDispositionRequest;
import com.flowops.api.dto.LegacyPlanDispositionResponse;
import com.flowops.application.execution.DisposeLegacyExecutionPlan;
import com.flowops.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/requests/{requestId}/legacy-plan-dispositions")
public class LegacyPlanDispositionController {

    private final DisposeLegacyExecutionPlan disposition;

    public LegacyPlanDispositionController(DisposeLegacyExecutionPlan disposition) {
        this.disposition = disposition;
    }

    @PostMapping
    @Operation(summary = "Review and adopt a historical approval's complete execution manifest, or keep it quarantined",
            description = "Requires scoped execution:plan:dispose. Unresolved jobs remain held.")
    public LegacyPlanDispositionResponse dispose(@PathVariable Long requestId,
            @RequestBody LegacyPlanDispositionRequest input, @AuthenticationPrincipal UserPrincipal caller) {
        return LegacyPlanDispositionResponse.from(disposition.dispose(requestId, input.toInput(), caller.toIdentity()));
    }

    @GetMapping
    @Operation(summary = "Read immutable legacy-plan disposition receipts",
            description = "Requires scoped execution:read. Receipts omit action input payloads.")
    public List<LegacyPlanDispositionResponse.Receipt> history(@PathVariable Long requestId,
            @RequestParam(defaultValue = "50") int limit, @AuthenticationPrincipal UserPrincipal caller) {
        return disposition.history(requestId, limit, caller.toIdentity())
            .stream()
            .map(LegacyPlanDispositionResponse.Receipt::from)
            .toList();
    }

}
