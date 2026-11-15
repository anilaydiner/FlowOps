package com.flowops.api;

import com.flowops.api.dto.ClarificationAnswerRequest;
import com.flowops.api.dto.ClarificationRequest;
import com.flowops.api.dto.ClarificationResponse;
import com.flowops.security.UserPrincipal;
import com.flowops.application.request.ClarificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/requests/{id}/clarifications")
public class ClarificationController {

    private final ClarificationService clarificationService;

    public ClarificationController(ClarificationService clarificationService) {
        this.clarificationService = clarificationService;
    }

    @PostMapping
    @Operation(summary = "Add a clarification question to a request",
            description = "Requester or admin only. The request must not be in a terminal state.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Clarification added"),
            @ApiResponse(responseCode = "404", description = "Request not found (or not the owner)"),
            @ApiResponse(responseCode = "409", description = "Request is in a terminal state")
    })
    public ResponseEntity<ClarificationResponse> ask(
            @PathVariable Long id,
            @Valid @RequestBody ClarificationRequest body,
            @AuthenticationPrincipal UserPrincipal caller) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ClarificationResponse.from(
                        clarificationService.ask(id, body.question(), caller.toIdentity())));
    }

    @GetMapping
    @Operation(summary = "List clarifications on a request",
            description = "Requester or admin only. Returns the full Q&A thread, newest first.")
    public List<ClarificationResponse> list(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal caller) {
        return clarificationService.listByRequest(id, caller.toIdentity()).stream()
                .map(ClarificationResponse::from)
                .toList();
    }

    @PutMapping("/{cid}")
    @Operation(summary = "Answer a clarification",
            description = "Requester or admin only. One-time; the clarification becomes read-only after answering.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Clarification answered"),
            @ApiResponse(responseCode = "404", description = "Clarification not found"),
            @ApiResponse(responseCode = "409", description = "Already answered or request is terminal")
    })
    public ClarificationResponse answer(
            @PathVariable Long id,
            @PathVariable Long cid,
            @Valid @RequestBody ClarificationAnswerRequest body,
            @AuthenticationPrincipal UserPrincipal caller) {
        return ClarificationResponse.from(
                clarificationService.answer(cid, id, body.answer(), caller.toIdentity()));
    }
}
