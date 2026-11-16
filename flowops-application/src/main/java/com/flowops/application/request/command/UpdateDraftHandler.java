package com.flowops.application.request.command;

import com.flowops.application.mediator.MessageHandler;
import com.flowops.application.model.RequestDetail;
import com.flowops.application.usecase.UpdateDraft;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UpdateDraftHandler implements MessageHandler<UpdateDraftCommand, RequestDetail> {
    private final UpdateDraft useCase;

    public UpdateDraftHandler(UpdateDraft useCase) {
        this.useCase = useCase;
    }

    @Override
    public Class<UpdateDraftCommand> messageType() {
        return UpdateDraftCommand.class;
    }

    @Override
    @Transactional
    public RequestDetail handle(UpdateDraftCommand command) {
        return RequestDetail.of(useCase.execute(command.id(), command.payload(), command.caller()));
    }
}
