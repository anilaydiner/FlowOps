package com.flowops.application.execution;

import com.flowops.application.execution.port.ProviderContractRepository;
import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.execution.*;
import com.flowops.domain.execution.port.ActionHandler;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Metadata only; no provider I/O while preparing durable commands. */
@Service
public class ProviderContracts {

    private final ActionHandler handler;

    private final ExecutionLeasePolicy timing;

    private final Duration reconciliationLease;

    private final ProviderContractRepository stored;

    public ProviderContracts(ActionHandler handler, ExecutionLeasePolicy timing, ProviderContractRepository stored,
            @Value("${flowops.execution.reconciliation-lease-seconds:60}") long seconds) {
        if (seconds < 1 || seconds > 86400)
            throw new IllegalArgumentException("Reconciliation lease must be 1–86400 seconds");
        this.handler = handler;
        this.timing = timing;
        this.stored = stored;
        reconciliationLease = Duration.ofSeconds(seconds);
    }

    public ProviderContract require(String action) {
        ProviderContract c;
        try {
            c = handler.contract(action);
        }
        catch (RuntimeException failure) {
            throw new ConflictException("provider-contract-invalid",
                    "Provider contract could not be validated for action " + action);
        }
        if (c == null)
            throw new ConflictException("provider-contract-unavailable",
                    "No provider timeout contract for action " + action);
        if (c.executionTimeout().compareTo(timing.maximumAttempt()) >= 0
                || c.reconciliationTimeout().compareTo(reconciliationLease) >= 0)
            throw new ConflictException("provider-contract-invalid",
                    "Provider execution and lookup budgets must leave room before their durable deadlines");
        return c;
    }

    public ProviderContract require(ExecutionJob job) {
        var c = require(job.getAction());
        stored.firstProvider(job.getId()).ifPresent(original -> {
            if (!original.equals(c.providerId()))
                throw new ConflictException("provider-binding-changed",
                        "The original action cannot be routed to a different provider");
        });
        return c;
    }

    public boolean retrySafe(ExecutionJob job) {
        return require(job).idempotentRetries();
    }

    public boolean hasRecordedProvider(ExecutionJob job) {
        return stored.firstProvider(job.getId()).isPresent();
    }

}
