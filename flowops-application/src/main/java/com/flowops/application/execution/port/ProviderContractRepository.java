package com.flowops.application.execution.port;

import com.flowops.domain.execution.ProviderContract;
import java.util.*;

public interface ProviderContractRepository {

    void recordAttempt(UUID token, String action, ProviderContract contract);

    void recordRecovery(UUID commandId, String action, ProviderContract contract);

    Optional<ProviderContract> attempt(UUID token);

    Optional<ProviderContract> recovery(UUID commandId);

    Optional<String> firstProvider(Long jobId);

}
