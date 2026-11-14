package com.flowops.infra;

import com.flowops.security.SecurityConfig;
import com.flowops.service.ConflictException;
import com.flowops.service.ForbiddenException;
import com.flowops.service.NotFoundException;
import com.flowops.service.RequestValidationException;
import com.flowops.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

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

    @ExceptionHandler(UserService.BadCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ProblemDetail handleBadCredentials(UserService.BadCredentialsException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "bad-credentials", "Invalid username or password");
    }

    @ExceptionHandler(ForbiddenException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ProblemDetail handleForbidden(ForbiddenException ex) {
        return problem(HttpStatus.FORBIDDEN, "forbidden", ex.getMessage());
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

    private ProblemDetail problem(HttpStatus status, String name, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(SecurityConfig.ERROR_BASE + name));
        return problem;
    }
}
