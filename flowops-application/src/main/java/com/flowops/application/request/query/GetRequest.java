package com.flowops.application.request.query;

import com.flowops.application.mediator.Message;
import com.flowops.application.request.model.RequestDetailView;
import com.flowops.domain.identity.CallerIdentity;

public record GetRequest(Long id, CallerIdentity caller) implements Message<RequestDetailView> {
}
