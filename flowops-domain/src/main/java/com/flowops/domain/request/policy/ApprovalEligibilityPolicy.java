package com.flowops.domain.request.policy;

import com.flowops.domain.identity.User;
import com.flowops.domain.identity.policy.AuthorizationDecision;
import com.flowops.domain.identity.policy.PermissionCodes;
import com.flowops.domain.identity.policy.PermissionScope;
import com.flowops.domain.identity.policy.TargetContext;
import com.flowops.domain.request.DecisionAction;
import com.flowops.domain.request.RequestStatus;
import com.flowops.domain.request.StepState;
import java.util.List;
import java.util.Set;

/** Pure approval eligibility. Persisted identity facts, never JWT role claims, are authoritative. */
public final class ApprovalEligibilityPolicy {
    private ApprovalEligibilityPolicy() {}

    public record Grant(String permission, String role, PermissionScope scope) {}

    public record Authority(Long actorId, boolean active, Set<String> roles, String departmentCode,
                            List<Grant> grants) {
        public Authority {
            roles = Set.copyOf(roles);
            grants = List.copyOf(grants);
        }
    }

    public record StepFacts(RequestStatus requestStatus, StepState stepState, String requiredRole,
                            TargetContext target, boolean delegatedToActor, boolean delegatedAway,
                            boolean alreadyApproved, boolean rosterSnapshotted, boolean rosterMember) {
        public StepFacts(RequestStatus requestStatus, StepState stepState, String requiredRole,
                         TargetContext target, boolean delegatedToActor, boolean delegatedAway, boolean alreadyApproved) {
            this(requestStatus, stepState, requiredRole, target, delegatedToActor, delegatedAway, alreadyApproved, false, false);
        }
    }

    /** Normalized permission union consumed by both the domain evaluator and SQL adapter. */
    public static Authority authority(User user, DecisionAction action) {
        String permission = action == DecisionAction.REJECT
                ? PermissionCodes.REQUEST_REJECT : PermissionCodes.REQUEST_APPROVE;
        return authorityFor(user, permission);
    }

    /** Normalize a permission union for scoped request queries as well as approvals. */
    public static Authority authorityFor(User user, String permission) {
        List<Grant> grants = user.getRoles().stream().flatMap(role -> role.getPermissions().stream()
                .filter(p -> p.getCode().equals(permission) || p.getCode().startsWith(permission + ":"))
                .map(p -> new Grant(p.getCode(), role.getCode(), new PermissionScope(p.getScopeType(), p.getScopeValue()))))
                .toList();
        return new Authority(user.getId(), user.isActive(), user.roleCodes(),
                user.getDepartment() == null ? null : user.getDepartment().getCode(), grants);
    }

    public static AuthorizationDecision evaluate(Authority actor, StepFacts step) {
        if (!actor.active()) return AuthorizationDecision.deny("account=inactive");
        if (step.requestStatus() != RequestStatus.IN_REVIEW || step.stepState() != StepState.PENDING) {
            return AuthorizationDecision.deny("step=not-active");
        }
        // Ordinary workflow decisions never inherit the generic identity evaluator's SoD override.
        if (actor.actorId().equals(step.target().requesterId())) {
            return AuthorizationDecision.deny("SoD: requester cannot act on own request");
        }
        if (step.alreadyApproved()) {
            return AuthorizationDecision.deny("vote=already-recorded");
        }
        if (step.delegatedAway()) {
            return AuthorizationDecision.deny("participation=delegated-away");
        }
        boolean participating = step.rosterSnapshotted() ? step.rosterMember() || step.delegatedToActor()
                : actor.roles().contains(step.requiredRole()) || step.delegatedToActor();
        if (!participating) {
            return AuthorizationDecision.deny("participation=required-role-missing");
        }
        return actor.grants().stream()
                .filter(g -> g.scope().matches(actor.actorId(), actor.departmentCode(), step.target()))
                .findFirst().map(g -> AuthorizationDecision.allow(List.of("permission=" + g.permission(),
                        "role=" + g.role(), "scope=" + (g.scope().type() == null ? "global" : g.scope().type()),
                        "participation=" + (step.rosterSnapshotted() ? "snapshot-slot" : step.delegatedToActor() ? "legacy-delegation" : "legacy-role"), "result=ALLOWED")))
                .orElseGet(() -> AuthorizationDecision.deny("permission/scope=no-matching-grant"));
    }
}
