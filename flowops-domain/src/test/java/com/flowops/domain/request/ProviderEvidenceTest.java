package com.flowops.domain.request;

import com.flowops.domain.execution.*;
import com.flowops.domain.execution.port.ActionHandler;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProviderEvidenceTest {
    @Test void authoritativeOutcomesRequireEvidenceAndFreezeProviderResults() {
        for(var outcome:List.of(ProviderObservation.Outcome.SUCCEEDED,ProviderObservation.Outcome.SAFE_TO_RETRY)) {
            assertThrows(IllegalArgumentException.class,()->new ProviderObservation(outcome," ",Map.of()));
        }
        var nested=new ArrayList<>(List.of("receipt"));
        var observation=new ProviderObservation(ProviderObservation.Outcome.SUCCEEDED,"provider:receipt",Map.of("receipts",nested));
        nested.clear();assertEquals(List.of("receipt"),observation.result().get("receipts"));
        assertThrows(UnsupportedOperationException.class,()->observation.result().clear());
    }
    @Test void adaptersWithoutReconciliationSupportCannotInferThatWorkNeverApplied() {
        ActionHandler adapter=command->Map.of();
        assertEquals(ProviderObservation.Outcome.UNKNOWN,adapter.reconcile(new ProviderQuery("key",1L,"ACTION")).outcome());
        assertFalse(adapter.supportsIdempotentRetries());
    }
}
