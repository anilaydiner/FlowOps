package com.flowops.application.request.lifecycle;

import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.User;
import com.flowops.domain.identity.policy.AuthorizationEvaluator;
import com.flowops.domain.identity.policy.PermissionCodes;
import com.flowops.domain.identity.policy.TargetContext;
import com.flowops.domain.identity.port.UserRepository;
import com.flowops.domain.request.Request;
import com.flowops.domain.request.RequestStatus;
import com.flowops.domain.request.StepInstance;
import com.flowops.domain.request.StepState;
import com.flowops.domain.request.port.RequestRepository;
import com.flowops.domain.request.port.StepInstanceRepository;
import com.flowops.domain.workflow.WorkflowStep;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use case: list the users a decision on the current step may be delegated to.
 *
 * <p>Only the <b>current approver</b> (a caller who may approve this request)
 * may see the list — and only users who would pass <b>exactly the same
 * authorization gate</b> they face when they act. A delegatee who cannot
 * approve would leave the request stuck, so they are never listed.
 *
 * <p>The list excludes the caller (delegating to yourself is a no-op) and the
 * requester (separation of duties), and only active users are considered.
 * Scoped permissions are honoured: a request type / department / threshold
 * scope that a candidate does not satisfy removes them from the list.
 */
@Component
public class EligibleDelegatees {

    private final RequestRepository requests;
    private final StepInstanceRepository stepInstances;
    private final UserRepository users;
    private final AuthorizationEvaluator authz;

    public EligibleDelegatees(RequestRepository requests,
                              StepInstanceRepository stepInstances,
                              UserRepository users,
                              AuthorizationEvaluator authz) {
        this.requests = requests;
        this.stepInstances = stepInstances;
        this.users = users;
        this.authz = authz;
    }

    /**
     * @return active users eligible to approve the request's current step,
     *         excluding the caller and the requester, sorted by username.
     * @throws com.flowops.domain.exception.ForbiddenException if the caller cannot approve this request
     * @throws NotFoundException if the request does not exist
     * @throws ConflictException if the request is not in review or has no active step
     */
    @Transactional(readOnly = true)
    public List<User> list(Long requestId, CallerIdentity caller) {
        Request request = requests.findById(requestId)
                .orElseThrow(() -> new NotFoundException("Request not found: " + requestId));
        if (request.getStatus() != RequestStatus.IN_REVIEW) {
            throw new ConflictException("Request is not in review (current: " + request.getStatus() + ")");
        }
        StepInstance current = stepInstances.findTopByRequestIdAndStateOrderById(requestId, StepState.PENDING)
                .orElseThrow(() -> new ConflictException("No active step to delegate on request " + requestId));
        WorkflowStep step = current.getStep();

        TargetContext target = TargetContext.from(request, step);

        // Only the current approver may see (or perform) a delegation.
        authz.require(caller, PermissionCodes.REQUEST_APPROVE, target);

        Long requesterId = request.getRequester().getId();
        return users.findAllWithDepartment().stream()
                .filter(User::isActive)
                .filter(u -> !u.getId().equals(caller.id()))
                .filter(u -> !u.getId().equals(requesterId))
                .filter(u -> authz.evaluate(u, PermissionCodes.REQUEST_APPROVE, target).allowed())
                .sorted(Comparator.comparing(User::getUsername))
                .toList();
    }
}
