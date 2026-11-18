package com.flowops.domain.request.port;

import com.flowops.domain.request.StepParticipant;
import java.util.List;

public interface StepParticipantRepository {
    void createInitialRoster(Long stepInstanceId, List<Long> userIds);
    List<StepParticipant> findByStepInstanceId(Long stepInstanceId);
    List<StepParticipant> findByRequestId(Long requestId);
    void recordRecovery(com.flowops.domain.request.StepRecovery recovery);
    List<com.flowops.domain.request.StepRecovery> findRecoveriesByRequestId(Long requestId);
    void appendRecoveryAssignment(Long stepInstanceId, java.util.UUID recoveryId,
                                  com.flowops.domain.request.RecoveryAssignment assignment);
}
