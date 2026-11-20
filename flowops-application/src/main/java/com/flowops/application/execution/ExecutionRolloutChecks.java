package com.flowops.application.execution;

import com.flowops.application.execution.port.*;
import com.flowops.domain.exception.ForbiddenException;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.identity.policy.*;
import com.flowops.domain.identity.port.UserRepository;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/**
 * Pre-enable/run-time machine checks, not an attestation of provider effects or
 * deployment acceptance.
 */
@Service
public class ExecutionRolloutChecks {

    public enum Phase {

        PRE_ENABLE, POST_ENABLE

    }

    public enum Status {

        PASS, BLOCKER, ATTENTION

    }

    public record Check(String code, Status status, long count, String summary) {
    }

    public record Contract(String action, Map<String, Object> contract) {
    }

    public record Report(Instant checkedAt, Phase phase, boolean machineChecksPassed, List<Check> checks,
            List<Contract> contracts, List<String> manualAcceptanceRequired) {
    }

    private static final Set<String> ATTENTION = Set.of("CLOSED_OUTCOME_UNKNOWN", "APPROVED_WITHOUT_FROZEN_PLAN",
            "UNVERIFIED_LEGACY_PROVIDER_ATTEMPTS", "UNCERTAIN_JOBS", "UNKNOWN_APPROVED_EXECUTION",
            "ORPHANED_RECOVERY_COMMANDS");

    private final ExecutionRolloutQueries queries;

    private final ProviderContracts contracts;

    private final ProviderCalls calls;

    private final ExecutionWorkerStatus worker;

    private final AuthorizationEvaluator authz;

    private final UserRepository users;

    private final Clock clock;

    private final boolean requireLive;

    public ExecutionRolloutChecks(ExecutionRolloutQueries queries, ProviderContracts contracts, ProviderCalls calls,
            ExecutionWorkerStatus worker, AuthorizationEvaluator authz, UserRepository users, Clock clock,
            @Value("${flowops.execution.rollout-require-live-provider:false}") boolean requireLive) {
        this.queries = queries;
        this.contracts = contracts;
        this.calls = calls;
        this.worker = worker;
        this.authz = authz;
        this.users = users;
        this.clock = clock;
        this.requireLive = requireLive;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Report check(Phase phase, CallerIdentity caller) {
        var user = users.findById(caller.id())
            .orElseThrow(() -> new ForbiddenException("Execution rollout access denied"));
        if (!user.isActive())
            throw new ForbiddenException("Inactive execution operator");
        boolean global = user.getRoles()
            .stream()
            .flatMap(role -> role.getPermissions().stream())
            .anyMatch(permission -> PermissionCodes.EXECUTION_ROLLOUT_READ.equals(permission.getCode())
                    && permission.isGlobal());
        if (!global)
            throw new ForbiddenException("Global execution rollout permission is required");
        authz.require(caller, PermissionCodes.EXECUTION_ROLLOUT_READ);
        var facts = queries.facts();
        var checks = new ArrayList<Check>();
        var descriptions = new ArrayList<Contract>();
        boolean complete = facts.counts().keySet().containsAll(ExecutionRolloutQueries.REQUIRED_CODES);
        checks.add(new Check("ROLLOUT_FACTS_INCOMPLETE", complete ? Status.PASS : Status.BLOCKER, complete ? 0 : 1,
                "Every required database acceptance fact must be present"));
        for (var e : new TreeMap<>(facts.counts()).entrySet()) {
            Status status = e.getValue() == 0 ? Status.PASS
                    : ATTENTION.contains(e.getKey()) ? Status.ATTENTION : Status.BLOCKER;
            if (e.getKey().equals("STARTED_LEASES") && phase == Phase.POST_ENABLE)
                status = Status.PASS;
            if (e.getKey().equals("EXPIRED_EXECUTION_LEASES") && phase == Phase.POST_ENABLE && e.getValue() > 0)
                status = Status.ATTENTION;
            checks.add(new Check(e.getKey(), status, e.getValue(), description(e.getKey())));
        }
        for (String action : new TreeSet<>(facts.requiredActions())) {
            try {
                var c = contracts.require(action);
                descriptions.add(new Contract(action, c.snapshot()));
                boolean mismatch = facts.recordedProviders()
                    .getOrDefault(action, Set.of())
                    .stream()
                    .anyMatch(id -> !id.equals(c.providerId()));
                checks.add(new Check("PROVIDER_CONTRACT:" + action, mismatch ? Status.BLOCKER : Status.PASS,
                        mismatch ? 1 : 0, mismatch ? "Configured provider differs from recorded active work"
                                : "Declared timeout budgets fit durable deadlines"));
                if (c.simulated())
                    checks
                        .add(new Check("SIMULATED_PROVIDER:" + action, requireLive ? Status.BLOCKER : Status.ATTENTION,
                                1, "This action uses a simulated provider"));
            }
            catch (RuntimeException invalid) {
                checks.add(new Check("PROVIDER_CONTRACT:" + action, Status.BLOCKER, 1,
                        "Missing or invalid timeout contract; no provider was invoked"));
            }
        }
        boolean workerOk = phase == Phase.PRE_ENABLE ? !worker.isRunning() : worker.isRunning();
        checks.add(new Check("WORKER_STATE", workerOk ? Status.PASS : Status.BLOCKER, workerOk ? 0 : 1,
                phase == Phase.PRE_ENABLE ? "Pause polling before pre-enable acceptance"
                        : "Execution polling must be enabled after rollout"));
        boolean completionOk = phase == Phase.PRE_ENABLE ? !worker.isCompletionReconciliationRunning()
                : worker.isCompletionReconciliationRunning();
        checks.add(new Check("COMPLETION_RECONCILER_STATE", completionOk ? Status.PASS : Status.BLOCKER,
                completionOk ? 0 : 1,
                phase == Phase.PRE_ENABLE ? "Pause completion reconciliation before pre-enable acceptance"
                        : "Periodic completion reconciliation must be enabled after rollout"));
        var capacity = calls.capacity();
        boolean capacityOk = phase == Phase.PRE_ENABLE ? capacity.executing() == 0 && capacity.lookingUp() == 0
                : capacity.executing() < capacity.executionSlots() && capacity.lookingUp() < capacity.lookupSlots();
        checks.add(new Check("PROVIDER_CAPACITY", capacityOk ? Status.PASS : Status.BLOCKER,
                capacity.executing() + capacity.lookingUp(),
                phase == Phase.PRE_ENABLE ? "Drain provider tasks, including timed-out calls, before acceptance"
                        : "Bounded execution and lookup pools must have free capacity"));
        return new Report(clock.instant(), phase, checks.stream().noneMatch(c -> c.status() == Status.BLOCKER),
                List.copyOf(checks), List.copyOf(descriptions),
                List.of("Confirm all old writers and pollers are stopped",
                        "Review every quarantine/legacy case and assign its operator",
                        "Record provider timeout, duplicate-key, late-result and reconciliation drill evidence",
                        "Verify provider/client transport timeouts and production credentials outside the report"));
    }

    private String description(String code) {
        return switch (code) {
            case "CLOSED_OUTCOME_UNKNOWN" ->
                "Historical closure has no unambiguous recorded outcome; review without inferring success from jobs";
            case "STARTED_LEASES" -> "Drain or reconcile active claims before enabling a new worker version";
            case "NO_GLOBAL_EXECUTION_OPERATOR" ->
                "At least one active global execution operator must hold read, reconcile and retry grants";
            case "UNKNOWN_APPROVED_EXECUTION", "UNVERIFIED_LEGACY_PROVIDER_ATTEMPTS" ->
                "Legacy evidence stays quarantined; historical provider guarantees are not invented";
            case "UNCERTAIN_JOBS", "ORPHANED_RECOVERY_COMMANDS" ->
                "Assign and review recovery work; this report does not resolve it";
            default -> "Zero violations required for " + code;
        };
    }

}
