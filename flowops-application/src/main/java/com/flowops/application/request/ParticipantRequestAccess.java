package com.flowops.application.request;

import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.request.RequestStatus;
import com.flowops.domain.request.StepState;
import com.flowops.domain.request.port.StepInstanceRepository;
import com.flowops.domain.request.port.RequestRepository;
import org.springframework.stereotype.Service;

/** A current decision assignment grants request context, never audit-log authority. */
@Service
public class ParticipantRequestAccess {
    private final StepInstanceRepository steps;
    private final ApprovalEligibilityService eligibility;
    private final RequestRepository requests;

    public ParticipantRequestAccess(StepInstanceRepository steps, ApprovalEligibilityService eligibility, RequestRepository requests) {
        this.steps = steps;
        this.eligibility = eligibility;
        this.requests = requests;
    }

    public boolean canRead(Long requestId, CallerIdentity caller) {
        var request = requests.findById(requestId).orElse(null);
        if (request == null || request.getStatus() != RequestStatus.IN_REVIEW) return false;
        var step = steps.findTopByRequestIdAndStateOrderById(requestId, StepState.PENDING).orElse(null);
        if (step == null) return false;
        return eligibility.canRead(eligibility.user(caller.id()), request, step);
    }
}
