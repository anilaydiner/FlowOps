package com.flowops.application.request.command;

import com.flowops.application.mediator.MessageHandler;
import com.flowops.application.request.RequestDetailReader;
import com.flowops.application.request.lifecycle.SubmitRequest;
import com.flowops.application.request.model.RequestDetail;
import com.flowops.application.request.port.RequestLifecycleObservability;
import com.flowops.domain.request.Request;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SubmitRequestHandler implements MessageHandler<SubmitRequestCommand, RequestDetail> {

    private final SubmitRequest useCase;

    private final RequestLifecycleObservability observability;

    private final RequestDetailReader details;

    public SubmitRequestHandler(SubmitRequest useCase, RequestLifecycleObservability observability,
            RequestDetailReader details) {
        this.useCase = useCase;
        this.observability = observability;
        this.details = details;
    }

    @Override
    public Class<SubmitRequestCommand> messageType() {
        return SubmitRequestCommand.class;
    }

    @Override
    @Transactional
    public RequestDetail handle(SubmitRequestCommand command) {
        Request result = observability.submit(command.id(), command.caller().username(),
                () -> useCase.execute(command.id(), command.caller()));
        return details.load(result);
    }

}
