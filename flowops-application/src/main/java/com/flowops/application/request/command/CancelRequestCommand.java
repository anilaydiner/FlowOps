package com.flowops.application.request.command;

import com.flowops.application.mediator.Message;
import com.flowops.application.request.model.RequestDetail;
import com.flowops.domain.identity.CallerIdentity;

public record CancelRequestCommand(Long id, CallerIdentity caller) implements Message<RequestDetail> {
}
