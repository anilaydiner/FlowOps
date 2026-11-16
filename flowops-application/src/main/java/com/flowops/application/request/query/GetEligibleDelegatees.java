package com.flowops.application.request.query;

import com.flowops.application.mediator.Message;
import com.flowops.domain.User;
import com.flowops.domain.identity.CallerIdentity;

import java.util.List;

public record GetEligibleDelegatees(Long id, CallerIdentity caller) implements Message<List<User>> {
}
