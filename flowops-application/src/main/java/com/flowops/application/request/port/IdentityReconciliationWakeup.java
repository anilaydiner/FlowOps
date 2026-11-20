package com.flowops.application.request.port;

/** Best-effort asynchronous wakeup; committed queue hints remain the source of truth. */
public interface IdentityReconciliationWakeup {

    void request();

}
