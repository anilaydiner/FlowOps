package com.flowops.application.request.command;

import com.flowops.application.mediator.MessageHandler;
import com.flowops.application.model.RequestDetail;
import com.flowops.application.usecase.CloseRequest;
import com.flowops.application.port.RequestLifecycleObservability;
import com.flowops.application.request.RequestDetailReader;
import com.flowops.domain.Request;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CloseRequestHandler implements MessageHandler<CloseRequestCommand, RequestDetail> {
    private final CloseRequest useCase;
    private final RequestLifecycleObservability observability;
    private final RequestDetailReader details;

    public CloseRequestHandler(CloseRequest useCase, RequestLifecycleObservability observability,
                              RequestDetailReader details) {
        this.useCase = useCase;
        this.observability = observability;
        this.details = details;
    }

    @Override
    public Class<CloseRequestCommand> messageType() {
        return CloseRequestCommand.class;
    }

    @Override
    @Transactional
    public RequestDetail handle(CloseRequestCommand command) {
        Request result = observability.close(command.id(), command.caller().username(),
                () -> useCase.execute(command.id(), command.caller()));
        return details.load(result);
    }
}
