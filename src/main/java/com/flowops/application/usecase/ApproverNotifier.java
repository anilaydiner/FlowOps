package com.flowops.application.usecase;

import com.flowops.domain.OutboxEvents;
import com.flowops.domain.StepInstance;
import com.flowops.domain.StepState;
import com.flowops.domain.User;
import com.flowops.domain.Request;
import com.flowops.domain.port.StepInstanceRepository;
import com.flowops.domain.port.UserRepository;
import com.flowops.domain.policy.AuthorizationEvaluator;
import com.flowops.domain.policy.PermissionCodes;
import com.flowops.domain.policy.TargetContext;
import com.flowops.application.service.EmailComposer;
import com.flowops.application.service.OutboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Notifies every user who is eligible to approve a request's current step.
 *
 * <p>Eligibility is the <b>same gate</b> the approver will face in
 * {@code DecideStep} (permission + scope + SoD via
 * {@link AuthorizationEvaluator#evaluate}), so nobody is emailed unless they
 * can actually act on the request — and no one who cannot act is. The
 * requester is only notified when they legitimately pass the gate (e.g. they
 * hold the SoD override).</p>
 *
 * <p>Delivery goes through the transactional outbox (FEED + EMAIL), keyed per
 * workflow step so an approver eligible at several steps of one request is
 * notified once per step, while a retried dispatch stays idempotent.</p>
 *
 * <p>No-op when the request has no active PENDING step (fully approved, or
 * the step is BLOCKED because no eligible approver exists).</p>
 */
@Component
public class ApproverNotifier {

    private static final Logger log = LoggerFactory.getLogger(ApproverNotifier.class);

    private final StepInstanceRepository stepInstances;
    private final UserRepository users;
    private final AuthorizationEvaluator authz;
    private final OutboxService outbox;
    private final EmailComposer emailComposer;

    public ApproverNotifier(StepInstanceRepository stepInstances,
                            UserRepository users,
                            AuthorizationEvaluator authz,
                            OutboxService outbox,
                            EmailComposer emailComposer) {
        this.stepInstances = stepInstances;
        this.users = users;
        this.authz = authz;
        this.outbox = outbox;
        this.emailComposer = emailComposer;
    }

    /**
     * Notify all eligible approvers of the request's current PENDING step.
     *
     * @param request        the request (must be loaded)
     * @param excludeUserId  user to skip (e.g. the approver who just advanced
     *                       the workflow — they don't need a nudge about it);
     *                       {@code null} to exclude nobody
     * @return the number of users notified
     */
    public int notifyEligibleApprovers(Request request, Long excludeUserId) {
        StepInstance current = stepInstances.findTopByRequestIdAndStateOrderById(request.getId(), StepState.PENDING)
                .orElse(null);
        if (current == null) {
            log.debug("APPROVER NOTIFY: request={} has no PENDING step — nothing to do", request.getId());
            return 0;
        }

        TargetContext target = TargetContext.from(request, current.getStep());
        EmailComposer.ComposedEmail email = emailComposer.approverRequested(request, current.getStep());

        int notified = 0;
        for (User user : users.findAllWithDepartment()) {
            if (!user.isActive()) continue;
            if (excludeUserId != null && excludeUserId.equals(user.getId())) continue;
            if (!authz.evaluate(user, PermissionCodes.REQUEST_APPROVE, target).allowed()) continue;

            outbox.notifyStepFeed(request.getId(), current.getId(), user.getId(),
                    OutboxEvents.NOTIFY_APPROVER_REQUEST, email.subject(), email.text(), null);
            outbox.notifyStepEmail(request.getId(), current.getId(), user.getId(),
                    OutboxEvents.APPROVER_REQUEST, email.subject(), email.text(), email.html(), null);
            notified++;
        }
        log.info("APPROVER NOTIFY: request={} step={} notified={}", request.getId(), current.getId(), notified);
        return notified;
    }
}
