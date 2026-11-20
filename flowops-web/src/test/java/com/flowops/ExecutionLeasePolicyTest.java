package com.flowops;

import com.flowops.application.execution.ExecutionLeasePolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionLeasePolicyTest {

    @Test
    void timingContractRequiresRoomForRenewalAndABoundedAttempt() {
        for (long[] timing : new long[][] { { 300, 0, 1800 }, { 300, 300, 1800 }, { 300, 30, 299 }, { 300, 30, 86401 },
                { -1, 1, 1800 } })
            assertThrows(IllegalArgumentException.class,
                    () -> new ExecutionLeasePolicy(timing[0], timing[1], timing[2]));
        var policy = new ExecutionLeasePolicy(300, 30, 1800);
        assertEquals(Duration.ofSeconds(300), policy.lease());
        assertEquals(Duration.ofSeconds(30), policy.heartbeatInterval());
        assertEquals(Duration.ofSeconds(1800), policy.maximumAttempt());
    }

}
