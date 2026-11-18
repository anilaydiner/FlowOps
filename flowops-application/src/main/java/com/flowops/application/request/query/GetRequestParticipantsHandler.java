package com.flowops.application.request.query;

import com.flowops.application.mediator.MessageHandler;
import com.flowops.domain.request.StepParticipant;
import com.flowops.domain.request.port.StepParticipantRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GetRequestParticipantsHandler implements MessageHandler<GetRequestParticipants, List<StepParticipant>> {
    private final GetRequestHandler requestReader;
    private final StepParticipantRepository participants;

    public GetRequestParticipantsHandler(GetRequestHandler requestReader, StepParticipantRepository participants) {
        this.requestReader = requestReader;
        this.participants = participants;
    }

    @Override public Class<GetRequestParticipants> messageType() { return GetRequestParticipants.class; }

    @Override @Transactional(readOnly = true)
    public List<StepParticipant> handle(GetRequestParticipants query) {
        // Reuse request-detail authorization; a roster is never an alternate way to discover a request.
        requestReader.handle(new GetRequest(query.id(), query.caller()));
        return participants.findByRequestId(query.id());
    }
}
