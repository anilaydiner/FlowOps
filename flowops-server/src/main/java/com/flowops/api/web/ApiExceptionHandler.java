package com.flowops.api.web;

import com.flowops.domain.exception.ConflictException;
import com.flowops.domain.exception.ForbiddenException;
import com.flowops.domain.exception.InvalidCredentialsException;
import com.flowops.domain.exception.InvalidStateTransitionException;
import com.flowops.domain.exception.NotFoundException;
import com.flowops.domain.exception.RequestValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps domain exceptions to RFC 7807 ProblemDetail responses.
 * (Validation handling lives in {@link ProblemDetailsAdvice}.)
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ProblemDetail handleNotFound(NotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "not-found", ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ProblemDetail handleConflict(ConflictException ex) {
        return problem(HttpStatus.CONFLICT, "conflict", ex.getMessage());
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ProblemDetail handleInvalidStateTransition(InvalidStateTransitionException ex) {
        return problem(HttpStatus.CONFLICT, "conflict", ex.getMessage());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ProblemDetail handleBadCredentials(InvalidCredentialsException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "bad-credentials", "Invalid username or password");
    }

    @ExceptionHandler(ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ProblemDetail handleForbidden(ForbiddenException ex) {
        ProblemDetail problem = problem(HttpStatus.FORBIDDEN, "forbidden", ex.getMessage());
        if (ex.getEvidence() != null && !ex.getEvidence().isEmpty()) {
            problem.setProperty("evidence", ex.getEvidence());
        }
        return problem;
    }

    @ExceptionHandler(RequestValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ProblemDetail handleRequestValidation(RequestValidationException ex) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation", ex.getMessage());
        problem.setProperty("violations", ex.getViolations());
        return problem;
    }

    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ProblemDetail handleOptimisticLock(org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
        return problem(HttpStatus.CONFLICT, "conflict", "Concurrent modification — reload and retry");
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ProblemDetail handleDataIntegrityViolation(org.springframework.dao.DataIntegrityViolationException ex) {
        return problem(HttpStatus.CONFLICT, "conflict", "Request conflicts with existing data");
    }

    private ProblemDetail problem(HttpStatus status, String name, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(ApiErrors.type(name));
        return problem;
    }
}
