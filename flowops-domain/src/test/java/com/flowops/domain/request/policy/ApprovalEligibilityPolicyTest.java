package com.flowops.domain.request.policy;

import com.flowops.domain.identity.ScopeType;
import com.flowops.domain.identity.policy.PermissionScope;
import com.flowops.domain.identity.policy.TargetContext;
import com.flowops.domain.request.RequestStatus;
import com.flowops.domain.request.StepState;
import com.flowops.domain.request.policy.ApprovalEligibilityPolicy.Authority;
import com.flowops.domain.request.policy.ApprovalEligibilityPolicy.Grant;
import com.flowops.domain.request.policy.ApprovalEligibilityPolicy.StepFacts;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class ApprovalEligibilityPolicyTest {
    private final TargetContext target = new TargetContext(1L, "EXPENSE", "FINANCE", 100.0, "REVIEW");

    private Authority actor(boolean active, Set<String> roles, PermissionScope... scopes) {
        return new Authority(2L, active, roles, "FINANCE", java.util.Arrays.stream(scopes)
                .map(scope -> new Grant("request:approve", "REVIEWER", scope)).toList());
    }

    private StepFacts step(TargetContext context, boolean incoming, boolean outgoing, boolean voted) {
        return new StepFacts(RequestStatus.IN_REVIEW, StepState.PENDING, "REVIEWER", context, incoming, outgoing, voted);
    }

    @Test
    void roleAndPermissionAreBothRequired() {
        var global = new PermissionScope(null, null);
        assertTrue(ApprovalEligibilityPolicy.evaluate(actor(true, Set.of("REVIEWER"), global), step(target, false, false, false)).allowed());
        assertFalse(ApprovalEligibilityPolicy.evaluate(actor(true, Set.of(), global), step(target, false, false, false)).allowed());
        assertFalse(ApprovalEligibilityPolicy.evaluate(actor(true, Set.of("REVIEWER")), step(target, false, false, false)).allowed());
        assertFalse(ApprovalEligibilityPolicy.evaluate(actor(false, Set.of("REVIEWER"), global), step(target, false, false, false)).allowed());
    }

    @Test
    void explicitDelegationSuppliesParticipationButNeverPermissionOrAnotherVote() {
        var authorized = actor(true, Set.of(), new PermissionScope(null, null));
        assertTrue(ApprovalEligibilityPolicy.evaluate(authorized, step(target, true, false, false)).allowed());
        assertFalse(ApprovalEligibilityPolicy.evaluate(actor(true, Set.of()), step(target, true, false, false)).allowed());
        assertFalse(ApprovalEligibilityPolicy.evaluate(authorized, step(target, true, true, false)).allowed());
        assertFalse(ApprovalEligibilityPolicy.evaluate(authorized, step(target, true, false, true)).allowed());
    }

    @Test
    void requesterCannotActEvenWithDelegationAndGlobalPermission() {
        var own = new TargetContext(2L, "EXPENSE", "FINANCE", 100.0, "REVIEW");
        assertFalse(ApprovalEligibilityPolicy.evaluate(actor(true, Set.of("REVIEWER"), new PermissionScope(null, null)),
                step(own, true, false, false)).allowed());
    }

    @Test
    void malformedOrAbsentThresholdContextFailsClosedButIndependentGlobalGrantCanAllow() {
        var missing = new TargetContext(1L, "IT_ACCESS", "IT", null, "REVIEW");
        assertFalse(new PermissionScope(ScopeType.THRESHOLD, "5000").matches(2L, "IT", missing));
        assertFalse(new PermissionScope(ScopeType.THRESHOLD, "oops").matches(2L, "FINANCE", target));
        assertFalse(new PermissionScope(ScopeType.THRESHOLD, "NaN").matches(2L, "FINANCE", target));
        assertTrue(ApprovalEligibilityPolicy.evaluate(actor(true, Set.of("REVIEWER"),
                new PermissionScope(ScopeType.THRESHOLD, "oops"), new PermissionScope(null, null)), step(missing, false, false, false)).allowed());
    }

    @ParameterizedTest
    @EnumSource(ScopeType.class)
    void eachScopeHasAnExplicitMatchingAndNonMatchingContext(ScopeType type) {
        String value = switch (type) {
            case REQUEST_TYPE -> "expense";
            case STEP -> "review";
            case THRESHOLD -> "100";
            default -> null;
        };
        var scope = new PermissionScope(type, value);
        assertTrue(scope.matches(1L, "finance", target));
        var mismatch = new TargetContext(3L, "OTHER", "OTHER", 101.0, "OTHER");
        assertFalse(scope.matches(1L, "FINANCE", mismatch));
    }

    @Test
    void blockedAndNonReviewRequestsCannotBeDecided() {
        var authorized = actor(true, Set.of("REVIEWER"), new PermissionScope(null, null));
        assertFalse(ApprovalEligibilityPolicy.evaluate(authorized, new StepFacts(RequestStatus.IN_REVIEW,
                StepState.BLOCKED, "REVIEWER", target, false, false, false)).allowed());
        assertFalse(ApprovalEligibilityPolicy.evaluate(authorized, new StepFacts(RequestStatus.CANCELLED,
                StepState.PENDING, "REVIEWER", target, false, false, false)).allowed());
    }
}
