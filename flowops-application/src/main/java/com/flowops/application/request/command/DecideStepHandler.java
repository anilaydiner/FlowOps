package com.flowops.application.request.command;

import com.flowops.application.mediator.MessageHandler;
import com.flowops.application.model.RequestDetail;
import com.flowops.application.usecase.DecideStep;
import com.flowops.application.port.RequestLifecycleObservability;
import com.flowops.application.request.RequestDetailReader;
import com.flowops.domain.Request;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DecideStepHandler implements MessageHandler<DecideStepCommand, RequestDetail> {
    private final DecideStep useCase;
    private final RequestLifecycleObservability observability;
    private final RequestDetailReader details;

    public DecideStepHandler(DecideStep useCase, RequestLifecycleObservability observability,
                              RequestDetailReader details) {
        this.useCase = useCase;
        this.observability = observability;
        this.details = details;
    }

    @Override
    public Class<DecideStepCommand> messageType() {
        return DecideStepCommand.class;
    }

    @Override
    @Transactional
    public RequestDetail handle(DecideStepCommand command) {
        Request result = observability.decide(command.id(), command.caller().username(), command.action(),
                () -> useCase.execute(command.id(), command.action(), command.justification(), command.caller(), command.delegatedTo()));
        return details.load(result);
    }
}
