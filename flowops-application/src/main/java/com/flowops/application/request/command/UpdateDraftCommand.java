package com.flowops.application.request.command;

import com.flowops.application.mediator.Message;
import com.flowops.application.request.model.RequestDetail;
import com.flowops.domain.identity.CallerIdentity;
import java.util.Map;

public record UpdateDraftCommand(Long id, Map<String, Object> payload,
        CallerIdentity caller) implements Message<RequestDetail> {
}
