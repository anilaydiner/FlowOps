package com.flowops.application.request.query;

import com.flowops.application.mediator.Message;
import com.flowops.domain.identity.CallerIdentity;
import com.flowops.domain.request.StepParticipant;
import java.util.List;

public record GetRequestParticipants(Long id, CallerIdentity caller) implements Message<List<StepParticipant>> {
}
