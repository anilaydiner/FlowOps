package com.flowops.application.request.command;

import com.flowops.application.mediator.MessageHandler;
import com.flowops.application.request.lifecycle.CancelRequest;
import com.flowops.application.request.model.RequestDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CancelRequestHandler implements MessageHandler<CancelRequestCommand, RequestDetail> {

    private final CancelRequest useCase;

    private final com.flowops.application.request.RequestDetailReader details;

    public CancelRequestHandler(CancelRequest useCase, com.flowops.application.request.RequestDetailReader details) {
        this.useCase = useCase;
        this.details = details;
    }

    @Override
    public Class<CancelRequestCommand> messageType() {
        return CancelRequestCommand.class;
    }

    @Override
    @Transactional
    public RequestDetail handle(CancelRequestCommand command) {
        return details.summary(useCase.execute(command.id(), command.caller()));
    }

}
