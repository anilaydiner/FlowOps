package com.flowops.application.request.command;

import com.flowops.application.mediator.Message;
import com.flowops.application.model.RequestDetail;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.DecisionAction;

public record DecideStepCommand(Long id, DecisionAction action, String justification, Long delegatedTo, CallerIdentity caller) implements Message<RequestDetail> {
}
