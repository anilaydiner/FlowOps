package com.flowops.application.execution.port;

import com.flowops.domain.execution.*;
import java.time.Instant;
import java.util.Map;

public interface ProviderCalls {
    Map<String,Object> execute(ActionCommand command,ProviderContract contract,Instant attemptDeadline);
    ProviderObservation reconcile(ProviderQuery query,ProviderContract contract,Instant recoveryDeadline);
    record Capacity(int executionSlots,int executing,int lookupSlots,int lookingUp) {}
    Capacity capacity();
}
