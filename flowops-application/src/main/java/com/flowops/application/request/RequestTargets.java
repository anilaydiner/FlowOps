package com.flowops.application.request;

import com.flowops.domain.identity.policy.TargetContext;
import com.flowops.domain.request.Request;
import com.flowops.domain.request.StepInstance;

public final class RequestTargets {
    private RequestTargets() {}

    public static TargetContext from(Request request, StepInstance step) {
        var type = request.getType();
        var department = type.getOwningDepartment();
        return TargetContext.forRequest(request.getRequester().getId(), type.getCode(),
                department == null ? null : department.getCode(), request.getPayload(),
                step == null ? null : step.getStepCode());
    }
}
