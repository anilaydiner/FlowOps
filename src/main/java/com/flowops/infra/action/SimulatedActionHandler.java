package com.flowops.infra.action;
import com.flowops.service.ActionHandler;

import com.flowops.domain.JobActions;
import com.flowops.domain.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Default {@link ActionHandler} for development and tests.
 *
 * <p>Simulates side-effect actions (provisioning, purchase ticket, accounting
 * export) by returning a canned result payload. Supports <b>failure injection</b>
 * for retry / dead-letter testing, mirroring {@code SimulatedEmailGateway}.
 */
@Component
public class SimulatedActionHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger(SimulatedActionHandler.class);

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
    public Map<String, Object> execute(Request request, String action) {
        if (failNext && failuresRemaining > 0) {
            failuresRemaining--;
            if (failuresRemaining == 0) failNext = false;
            log.info("ACTION INJECTED FAILURE: action={} request={}", action, request.getId());
            throw new ActionExecutionException("Simulated failure for " + action);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SIMULATED_OK");
        result.put("action", action);
        result.put("requestId", request.getId());
        result.put("timestamp", Instant.now().toString());

        switch (action) {
            case JobActions.PROVISIONING -> {
                result.put("provisioned", true);
                result.put("resource", String.valueOf(request.getPayload().getOrDefault("resource", "default")));
            }
            case JobActions.PURCHASE_TICKET -> {
                result.put("ticketId", "TCK-" + request.getId());
            }
            case JobActions.ACCOUNTING_EXPORT -> {
                result.put("exportId", "EXP-" + request.getId());
            }
            default -> {
                log.warn("ACTION UNKNOWN: {}", action);
                result.put("note", "No specific simulation for " + action);
            }
        }

        log.info("ACTION SIMULATED: action={} request={} result={}", action, request.getId(), result);
        return result;
    }
}
