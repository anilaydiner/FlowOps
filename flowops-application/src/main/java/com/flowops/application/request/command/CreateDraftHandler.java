package com.flowops.application.request.command;

import com.flowops.application.mediator.MessageHandler;
import com.flowops.application.model.RequestDetail;
import com.flowops.application.usecase.CreateDraft;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreateDraftHandler implements MessageHandler<CreateDraftCommand, RequestDetail> {
    private final CreateDraft useCase;

    public CreateDraftHandler(CreateDraft useCase) {
        this.useCase = useCase;
    }

    @Override
    public Class<CreateDraftCommand> messageType() {
        return CreateDraftCommand.class;
    }

    @Override
    @Transactional
    public RequestDetail handle(CreateDraftCommand command) {
        return RequestDetail.of(useCase.execute(command.typeCode(), command.payload(), command.caller()));
    }
}
