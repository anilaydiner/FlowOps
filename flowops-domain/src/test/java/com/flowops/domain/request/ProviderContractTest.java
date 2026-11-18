package com.flowops.domain.request;

import com.flowops.domain.execution.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProviderContractTest {
    @Test void networkBudgetsMustBeExplicitAndFitTheTotalExecutionDeadline(){
        assertThrows(IllegalArgumentException.class,()->new ProviderContract("provider","v1",ProviderContract.Transport.NETWORK,Duration.ZERO,Duration.ofSeconds(2),Duration.ofSeconds(3),Duration.ofSeconds(1),false,true,false));
        assertThrows(IllegalArgumentException.class,()->new ProviderContract("provider","v1",ProviderContract.Transport.NETWORK,Duration.ofSeconds(2),Duration.ofSeconds(2),Duration.ofSeconds(3),Duration.ofSeconds(1),false,true,false));
        var c=new ProviderContract("provider","v1",ProviderContract.Transport.NETWORK,Duration.ofMillis(100),Duration.ofSeconds(2),Duration.ofSeconds(3),Duration.ofSeconds(1),false,true,false);
        var call=new ProviderCall(c,Instant.now().plusMillis(50),Duration.ofMillis(50));
        assertEquals(Duration.ofMillis(50),call.connectTimeout());assertEquals(Duration.ofMillis(50),call.readTimeout());
    }
    @Test void immutableSnapshotPreservesProviderIdentityAndDeclaredGuarantees(){
        var c=new ProviderContract("provider","v1",ProviderContract.Transport.LOCAL,Duration.ZERO,Duration.ZERO,Duration.ofSeconds(3),Duration.ofSeconds(1),true,true,false);
        assertEquals(c,ProviderContract.fromSnapshot(c.snapshot()));assertThrows(UnsupportedOperationException.class,()->c.snapshot().clear());
    }
}
