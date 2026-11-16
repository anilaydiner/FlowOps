package com.flowops.application.request.query;

import com.flowops.application.mediator.MessageHandler;
import com.flowops.application.request.lifecycle.EligibleDelegatees;
import com.flowops.domain.identity.User;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetEligibleDelegateesHandler implements MessageHandler<GetEligibleDelegatees, List<User>> {
    private final EligibleDelegatees useCase;

    public GetEligibleDelegateesHandler(EligibleDelegatees useCase) {
        this.useCase = useCase;
    }

    @Override
    public Class<GetEligibleDelegatees> messageType() {
        return GetEligibleDelegatees.class;
    }

    @Override
    @Transactional(readOnly = true)
    public List<User> handle(GetEligibleDelegatees query) {
        return useCase.list(query.id(), query.caller());
    }
}
