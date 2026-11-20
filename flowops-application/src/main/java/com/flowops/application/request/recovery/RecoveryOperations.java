package com.flowops.application.request.recovery;

import com.flowops.application.request.RequestTargets;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.identity.User;
import com.flowops.domain.identity.policy.AuthorizationEvaluator;
import com.flowops.domain.identity.policy.PermissionCodes;
import com.flowops.domain.identity.policy.TargetContext;
import com.flowops.domain.identity.port.UserRepository;
import com.flowops.domain.request.Request;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Configured recovery team with an explicitly required active fallback. */
@Service
public class RecoveryOperations {

    private static final Logger log = LoggerFactory.getLogger(RecoveryOperations.class);

    private final UserRepository users;

    private final AuthorizationEvaluator authz;

    private final String teamRole;

    private final String fallbackUsername;

    public RecoveryOperations(UserRepository users, AuthorizationEvaluator authz,
            @Value("${flowops.recovery.team-role:WORKFLOW_OPERATIONS}") String teamRole,
            @Value("${flowops.recovery.fallback-username:admin}") String fallbackUsername) {
        this.users = users;
        this.authz = authz;
        this.teamRole = teamRole;
        this.fallbackUsername = fallbackUsername;
    }

    public record Responsibility(Long ownerId, String team, List<Long> recipients) {
    }

    public void validateConfiguration() {
        resolve(null);
    }

    public Responsibility resolve(Request request) {
        return resolve(request, null);
    }

    /**
     * Configuration validation remains strict; runtime outages must not prevent safe
     * blocking.
     */
    public Responsibility resolve(Request request, com.flowops.domain.request.StepInstance step) {
        var target = target(request, step);
        users.findByUsername(fallbackUsername)
            .filter(u -> authorized(u, target))
            .orElseThrow(
                    () -> new ConflictException("Workflow recovery requires an active, authorized fallback operator"));
        return available(request, step);
    }

    public Responsibility available(Request request, com.flowops.domain.request.StepInstance step) {
        var target = target(request, step);
        var team = users.findActiveByRoleCode(teamRole)
            .stream()
            .filter(u -> authorized(u, target))
            .sorted(java.util.Comparator.comparing(User::getId))
            .toList();
        var fallback = users.findByUsername(fallbackUsername).filter(u -> authorized(u, target));
        var recipients = Stream.concat(team.stream().map(User::getId), fallback.stream().map(User::getId))
            .distinct()
            .toList();
        if (recipients.isEmpty())
            log.error("WORKFLOW OPERATIONS: no authorized recovery owner is available for team {}", teamRole);
        return new Responsibility(recipients.isEmpty() ? null : recipients.getFirst(), teamRole, recipients);
    }

    public boolean eligibleOwner(Request request, com.flowops.domain.request.StepInstance step, Long id) {
        if (id == null)
            return false;
        return users.findById(id)
            .filter(u -> authorized(u, target(request, step)))
            .filter(u -> u.getUsername().equals(fallbackUsername) || u.roleCodes().contains(teamRole))
            .isPresent();
    }

    private TargetContext target(Request request, com.flowops.domain.request.StepInstance step) {
        if (request == null)
            return null;
        return RequestTargets.from(request, step);
    }

    private boolean authorized(User user, TargetContext target) {
        return user.isActive() && authz.evaluate(user, PermissionCodes.REQUEST_RECOVER, target, true).allowed()
                && authz.evaluate(user, PermissionCodes.REQUEST_BLOCKED_READ, target, true).allowed();
    }

}
