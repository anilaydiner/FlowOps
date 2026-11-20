package com.flowops.application.execution.port;

public interface ExecutionWorkerStatus {

    boolean isRunning();

    default boolean isCompletionReconciliationRunning() {
        return false;
    }

}
