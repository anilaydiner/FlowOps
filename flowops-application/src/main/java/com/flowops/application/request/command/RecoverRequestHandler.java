package com.flowops.application.request.command;

import com.flowops.application.mediator.MessageHandler;
import com.flowops.application.request.RequestDetailReader;
import com.flowops.application.request.model.RequestDetail;
import com.flowops.application.request.recovery.RecoverBlockedRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecoverRequestHandler implements MessageHandler<RecoverRequestCommand, RequestDetail> {

    private final RecoverBlockedRequest recovery;

    private final RequestDetailReader details;

    public RecoverRequestHandler(RecoverBlockedRequest recovery, RequestDetailReader details) {
        this.recovery = recovery;
        this.details = details;
    }

    @Override
    public Class<RecoverRequestCommand> messageType() {
        return RecoverRequestCommand.class;
    }

    @Override
    @Transactional
    public RequestDetail handle(RecoverRequestCommand c) {
        var request = c.recheckOnly()
                ? recovery.recheck(c.id(), c.stepInstanceId(), c.expectedVersion(), c.reason(), c.caller())
                : recovery.recover(c.id(), c.stepInstanceId(), c.expectedVersion(), c.reason(), c.refreshPool(),
                        c.assignments(), c.caller());
        return details.load(request);
    }

}
