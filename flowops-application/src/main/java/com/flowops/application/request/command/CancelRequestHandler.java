package com.flowops.application.request.command;

import com.flowops.application.mediator.MessageHandler;
import com.flowops.application.model.RequestDetail;
import com.flowops.application.usecase.CancelRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CancelRequestHandler implements MessageHandler<CancelRequestCommand, RequestDetail> {
    private final CancelRequest useCase;

    public CancelRequestHandler(CancelRequest useCase) {
        this.useCase = useCase;
    }

    @Override
    public Class<CancelRequestCommand> messageType() {
        return CancelRequestCommand.class;
    }

    @Override
    @Transactional
    public RequestDetail handle(CancelRequestCommand command) {
        return RequestDetail.of(useCase.execute(command.id(), command.caller()));
    }
}
