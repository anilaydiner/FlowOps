package com.flowops.application.request.recovery;

import com.flowops.application.request.ApprovalEligibilityService;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.policy.*;
import com.flowops.domain.request.StepState;
import com.flowops.domain.request.port.StepInstanceRepository;
import org.springframework.stereotype.Service;

@Service
public class BlockedRequestAccess {
    private final ApprovalEligibilityService eligibility;
    private final StepInstanceRepository steps;
    private final AuthorizationEvaluator authz;
    public BlockedRequestAccess(ApprovalEligibilityService eligibility, StepInstanceRepository steps, AuthorizationEvaluator authz) {
        this.eligibility = eligibility; this.steps = steps; this.authz = authz;
    }

    public boolean canRead(Long requestId, CallerIdentity caller, TargetContext target) {
        var step = steps.findTopByRequestIdAndStateOrderById(requestId, StepState.BLOCKED).orElse(null);
        if (step == null || !eligibility.user(caller.id()).isActive()) return false;
        var scoped = new TargetContext(target.requesterId(), target.typeCode(), target.departmentCode(), target.amount(), step.getStepCode());
        return authz.authorize(caller, PermissionCodes.REQUEST_BLOCKED_READ, scoped, true).allowed();
    }
}
