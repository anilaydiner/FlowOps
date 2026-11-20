package com.flowops.application.request.command;

import com.flowops.application.mediator.Message;
import com.flowops.application.request.model.RequestDetail;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.request.DecisionAction;

public record DecideStepCommand(Long id, DecisionAction action, String justification, Long delegatedTo,
        CallerIdentity caller, Long stepInstanceId, Long expectedVersion) implements Message<RequestDetail> {
    public DecideStepCommand(Long id, DecisionAction action, String justification, Long delegatedTo,
            CallerIdentity caller) {
        this(id, action, justification, delegatedTo, caller, null, null);
    }
}
