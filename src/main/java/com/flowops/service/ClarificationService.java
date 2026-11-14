package com.flowops.service;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.exception.ConflictException;

import com.flowops.domain.Clarification;
import com.flowops.domain.ClarificationRepository;
import com.flowops.domain.Request;
import com.flowops.domain.RequestRepository;
import com.flowops.domain.RequestStatus;
import com.flowops.security.UserPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Clarification Q&A on a request (PLAN §8, iteration 7).
 *
 * <p>Access rules (consistent with the rest of the request API):
 * <ul>
 *   <li><b>Ask</b>: requester or admin, on a non-terminal request</li>
 *   <li><b>Answer</b>: requester or admin, on an unanswered clarification</li>
 *   <li><b>List</b>: requester or admin</li>
 * </ul>
 */
@Service
public class ClarificationService {

    private final ClarificationRepository clarifications;
    private final RequestRepository requests;

    public ClarificationService(ClarificationRepository clarifications,
                                RequestRepository requests) {
        this.clarifications = clarifications;
        this.requests = requests;
    }

    @Transactional
    public Clarification ask(Long requestId, String question, UserPrincipal caller) {
        Request request = loadAndCheckAccess(requestId, caller);
        if (request.getStatus().isTerminal()) {
            throw new ConflictException("Cannot add clarifications to a terminal request: " + request.getStatus());
        }
        Clarification c = new Clarification(request.getId(), caller.id(), question);
        return clarifications.save(c);
    }

    @Transactional
    public Clarification answer(Long clarificationId, Long requestId, String answer, UserPrincipal caller) {
        Request request = loadAndCheckAccess(requestId, caller);
        if (request.getStatus().isTerminal()) {
            throw new ConflictException("Cannot answer clarifications on a terminal request: " + request.getStatus());
        }
        Clarification c = clarifications.findByIdAndRequestId(clarificationId, requestId)
                .orElseThrow(() -> new NotFoundException("Clarification not found: " + clarificationId));
        if (c.isAnswered()) {
            throw new ConflictException("Clarification already answered");
        }
        c.answer(answer, caller.id());
        return clarifications.save(c);
    }

    @Transactional(readOnly = true)
    public List<Clarification> listByRequest(Long requestId, UserPrincipal caller) {
        Request request = loadAndCheckAccess(requestId, caller);
        return clarifications.findByRequestIdOrderByCreatedAtDesc(request.getId());
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private Request loadAndCheckAccess(Long requestId, UserPrincipal caller) {
        Request request = requests.findById(requestId)
                .orElseThrow(() -> new NotFoundException("Request not found: " + requestId));
        if (!caller.isAdmin() && !caller.id().equals(request.getRequester().getId())) {
            throw new NotFoundException("Request not found: " + requestId);
        }
        return request;
    }
}
