package com.flowops.application.execution.port;

import java.time.Duration;

public interface ExecutionHeartbeatSettings {
    Duration heartbeatInterval();
}
