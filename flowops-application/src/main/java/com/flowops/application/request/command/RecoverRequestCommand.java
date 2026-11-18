package com.flowops.application.request.command;

import com.flowops.application.mediator.Message;
import com.flowops.application.request.model.RequestDetail;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.request.RecoveryAssignment;
import java.util.List;

public record RecoverRequestCommand(Long id, Long stepInstanceId, Long expectedVersion, String reason,
                                    boolean refreshPool, List<RecoveryAssignment> assignments,
                                    boolean recheckOnly, CallerIdentity caller) implements Message<RequestDetail> {}
