package com.flowops.action;

import com.flowops.domain.execution.JobActions;
import com.flowops.domain.execution.port.ActionHandler;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default {@link ActionHandler} for development and tests.
 *
 * <p>
 * Simulates side-effect actions (provisioning, purchase ticket, accounting export) by
 * returning a canned result payload. Supports <b>failure injection</b> for retry /
 * dead-letter testing, mirroring {@code SimulatedEmailGateway}.
 */
@Component
public class SimulatedActionHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(SimulatedActionHandler.class);

    private final Map<String, com.flowops.domain.execution.ProviderContract> contracts;

    public SimulatedActionHandler(
            @org.springframework.beans.factory.annotation.Value("${flowops.execution.simulated.provisioning-timeout-seconds:30}") long provisioning,
            @org.springframework.beans.factory.annotation.Value("${flowops.execution.simulated.purchase-timeout-seconds:20}") long purchase,
            @org.springframework.beans.factory.annotation.Value("${flowops.execution.simulated.accounting-timeout-seconds:15}") long accounting,
            @org.springframework.beans.factory.annotation.Value("${flowops.execution.simulated.lookup-timeout-seconds:10}") long lookup) {
        contracts = Map.of(JobActions.PROVISIONING, contract(provisioning, lookup), JobActions.PURCHASE_TICKET,
                contract(purchase, lookup), JobActions.ACCOUNTING_EXPORT, contract(accounting, lookup));
    }

    private com.flowops.domain.execution.ProviderContract contract(long execution, long lookup) {
        return new com.flowops.domain.execution.ProviderContract("SIMULATED", "v1",
                com.flowops.domain.execution.ProviderContract.Transport.LOCAL, java.time.Duration.ZERO,
                java.time.Duration.ZERO, java.time.Duration.ofSeconds(execution), java.time.Duration.ofSeconds(lookup),
                true, true, true);
    }

    @Override
    public com.flowops.domain.execution.ProviderContract contract(String action) {
        return contracts.get(action);
    }

    private record Stored(com.flowops.domain.execution.ActionCommand command, Map<String, Object> result) {
    }

    private final java.util.concurrent.ConcurrentMap<String, Stored> outcomes = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public com.flowops.domain.execution.ProviderObservation reconcile(
            com.flowops.domain.execution.ProviderQuery query) {
        var found = outcomes.get(query.idempotencyKey());
        if (found == null || !found.command().requestId().equals(query.requestId())
                || !found.command().action().equals(query.action()))
            return com.flowops.domain.execution.ProviderObservation.unknown();
        return new com.flowops.domain.execution.ProviderObservation(
                com.flowops.domain.execution.ProviderObservation.Outcome.SUCCEEDED,
                "simulated:" + query.idempotencyKey(), found.result());
    }

    private volatile boolean failNext = false;

    private volatile int failuresRemaining = 0;

    /** Enable failure injection: the next {@code count} calls throw. */
    public void setSimulatedFailure(boolean fail, int count) {
        this.failNext = fail;
        this.failuresRemaining = count;
    }

    public void clearSimulatedFailure() {
        this.failNext = false;
        this.failuresRemaining = 0;
    }

    @Override
    public boolean supportsIdempotentRetries() {
        return true;
    }

    @Override
    public Map<String, Object> execute(com.flowops.domain.execution.ActionCommand command) {
        var prior = outcomes.get(command.idempotencyKey());
        if (prior != null)
            return sameCommand(prior, command);
        String action = command.action();
        if (failNext && failuresRemaining > 0) {
            failuresRemaining--;
            if (failuresRemaining == 0)
                failNext = false;
            log.info("ACTION INJECTED FAILURE: action={} request={}", action, command.requestId());
            throw new ActionExecutionException("Simulated failure for " + action);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SIMULATED_OK");
        result.put("idempotencyKey", command.idempotencyKey());
        result.put("action", action);
        result.put("requestId", command.requestId());
        result.put("timestamp", Instant.now().toString());

        switch (action) {
            case JobActions.PROVISIONING -> {
                result.put("provisioned", true);
                result.put("resource", String.valueOf(command.payload().getOrDefault("resource", "default")));
            }
            case JobActions.PURCHASE_TICKET -> {
                result.put("ticketId", "TCK-" + command.requestId());
            }
            case JobActions.ACCOUNTING_EXPORT -> {
                result.put("exportId", "EXP-" + command.requestId());
            }
            default -> {
                log.warn("ACTION UNKNOWN: {}", action);
                result.put("note", "No specific simulation for " + action);
            }
        }

        log.info("ACTION SIMULATED: action={} request={} result={}", action, command.requestId(), result);
        var stored = new Stored(command, com.flowops.domain.execution.ActionCommand.freeze(result));
        var winner = outcomes.putIfAbsent(command.idempotencyKey(), stored);
        return winner == null ? stored.result() : sameCommand(winner, command);
    }

    private Map<String, Object> sameCommand(Stored stored, com.flowops.domain.execution.ActionCommand command) {
        if (!stored.command().equals(command))
            throw new ActionExecutionException("Idempotency key reused with different command");
        return stored.result();
    }

}
