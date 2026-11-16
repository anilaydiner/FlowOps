package com.flowops.application.request.command;

import com.flowops.application.mediator.Message;
import com.flowops.application.model.RequestDetail;
import com.flowops.domain.identity.CallerIdentity;

import java.util.Map;

public record CreateDraftCommand(String typeCode, Map<String, Object> payload, CallerIdentity caller) implements Message<RequestDetail> {
}
