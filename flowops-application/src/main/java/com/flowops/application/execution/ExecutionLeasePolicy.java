package com.flowops.application.execution;

import com.flowops.application.execution.port.ExecutionHeartbeatSettings;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** One validated timing contract shared by start, heartbeat and the scheduling adapter. */
@Component
public class ExecutionLeasePolicy implements ExecutionHeartbeatSettings {
    private final Duration lease;
    private final Duration heartbeat;
    private final Duration maximumAttempt;
    public ExecutionLeasePolicy(@Value("${flowops.execution.lease-seconds:300}") long leaseSeconds,
                                @Value("${flowops.execution.heartbeat-seconds:30}") long heartbeatSeconds,
                                @Value("${flowops.execution.max-attempt-seconds:1800}") long maxSeconds) {
        if(heartbeatSeconds<1 || heartbeatSeconds>=leaseSeconds || leaseSeconds>maxSeconds || maxSeconds>86400)
            throw new IllegalArgumentException("Execution timing must satisfy 1 <= heartbeat-seconds < lease-seconds <= max-attempt-seconds <= 86400");
        lease=Duration.ofSeconds(leaseSeconds);heartbeat=Duration.ofSeconds(heartbeatSeconds);maximumAttempt=Duration.ofSeconds(maxSeconds);
    }
    public Duration lease() { return lease; }
    @Override public Duration heartbeatInterval() { return heartbeat; }
    public Duration maximumAttempt() { return maximumAttempt; }
}
