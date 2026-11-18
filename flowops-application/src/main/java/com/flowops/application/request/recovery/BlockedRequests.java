package com.flowops.application.request.recovery;

import com.flowops.application.request.ApprovalEligibilityService;
import com.flowops.application.request.model.BlockedRequestView;
import com.flowops.application.request.port.BlockedRequestQueries;
import com.flowops.domain.exception.ForbiddenException;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.policy.PermissionCodes;
import com.flowops.domain.paging.*;
import com.flowops.domain.request.policy.ApprovalEligibilityPolicy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BlockedRequests {
    private final ApprovalEligibilityService eligibility;
    private final BlockedRequestQueries queries;
    private final com.flowops.domain.request.port.RequestRepository requests;
    private final com.flowops.domain.request.port.StepParticipantRepository participants;
    private final com.flowops.domain.identity.policy.AuthorizationEvaluator authz;
    private final com.flowops.domain.request.port.StepInstanceRepository steps;
    public BlockedRequests(ApprovalEligibilityService eligibility, BlockedRequestQueries queries,
                           com.flowops.domain.request.port.RequestRepository requests,
                           com.flowops.domain.request.port.StepParticipantRepository participants,
                           com.flowops.domain.identity.policy.AuthorizationEvaluator authz,
                           com.flowops.domain.request.port.StepInstanceRepository steps) {
        this.steps = steps;
        this.eligibility = eligibility;
        this.queries = queries;
        this.requests = requests;
        this.participants = participants;
        this.authz = authz;
    }

    @Transactional(readOnly = true)
    public PageResult<BlockedRequestView> list(PageQuery page, BlockedRequestQueries.Filter filter, CallerIdentity caller) {
        var authority = ApprovalEligibilityPolicy.authorityFor(eligibility.user(caller.id()), PermissionCodes.REQUEST_BLOCKED_READ);
        if (!authority.active() || authority.grants().isEmpty()) throw new ForbiddenException("Missing blocked-request operator access");
        return queries.findPage(page, filter, authority);
    }

    @Transactional(readOnly = true)
    public java.util.List<com.flowops.domain.request.StepRecovery> history(Long requestId, CallerIdentity caller) {
        var request = requests.findById(requestId).orElseThrow(() -> new com.flowops.domain.exception.NotFoundException("Request not found: " + requestId));
        if (!eligibility.user(caller.id()).isActive()) throw new ForbiddenException("Inactive recovery operator");
        var contexts = steps.findByRequestIdOrderById(requestId).stream().collect(java.util.stream.Collectors.toMap(
                com.flowops.domain.request.StepInstance::getId, step -> ApprovalEligibilityService.target(request, step)));
        var readable = contexts.entrySet().stream().filter(entry -> authz.authorize(caller,
                PermissionCodes.REQUEST_BLOCKED_READ, entry.getValue(), true).allowed())
                .map(java.util.Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
        if (readable.isEmpty()) throw new ForbiddenException("Missing blocked-request operator access");
        return participants.findRecoveriesByRequestId(requestId).stream().filter(r -> readable.contains(r.stepInstanceId())).toList();
    }
}
