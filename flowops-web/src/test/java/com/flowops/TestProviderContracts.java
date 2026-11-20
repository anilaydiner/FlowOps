package com.flowops;

import com.flowops.domain.execution.*;
import com.flowops.domain.execution.port.ActionHandler;
import java.time.Duration;
import static org.mockito.Mockito.*;

final class TestProviderContracts {

    private TestProviderContracts() {
    }

    static void stub(ActionHandler handler) {
        when(handler.contract(anyString())).thenAnswer(call -> new ProviderContract("TEST_PROVIDER", "v1",
                ProviderContract.Transport.LOCAL, Duration.ZERO, Duration.ZERO, Duration.ofSeconds(20),
                Duration.ofSeconds(10), handler.supportsIdempotentRetries(), true, false));
        when(handler.execute(any(), any())).thenAnswer(call -> handler.execute(call.getArgument(0)));
        when(handler.reconcile(any(), any())).thenAnswer(call -> handler.reconcile(call.getArgument(0)));
    }

}
