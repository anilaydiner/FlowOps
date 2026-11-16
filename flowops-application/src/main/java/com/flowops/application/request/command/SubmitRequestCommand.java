package com.flowops.application.request.command;

import com.flowops.application.mediator.Message;
import com.flowops.application.model.RequestDetail;
import com.flowops.domain.identity.CallerIdentity;

public record SubmitRequestCommand(Long id, CallerIdentity caller) implements Message<RequestDetail> {
}
