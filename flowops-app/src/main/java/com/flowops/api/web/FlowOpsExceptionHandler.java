package com.flowops.api.web;

import com.flowops.domain.CorrelationId;
import com.flowops.domain.exception.ApiErrors;

import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Customizes MVC exception handling by extending {@link ResponseEntityExceptionHandler}.
 * Spring Boot backs off from registering its built-in ProblemDetailsExceptionHandler
 * whenever a {@code ResponseEntityExceptionHandler} bean exists, so this class owns
 * validation failures (400) and friends, emitting RFC 7807 bodies that include:
 * - per-field {@code violations} for validation errors,
 * - the {@code correlationId} of the request.
 *
 * Spring 7 builds validation bodies from the exception's own ErrorResponse payload,
 * so the handlers are overridden directly rather than via createProblemDetail.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class FlowOpsExceptionHandler extends ResponseEntityExceptionHandler {

    private static final URI VALIDATION = ApiErrors.type("validation");

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                   HttpHeaders headers, HttpStatusCode status,
                                                                   WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setType(VALIDATION);
        Map<String, String> violations = new HashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            violations.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        problem.setProperty("violations", violations);
        return validationResponse(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
                                                                            HttpHeaders headers, HttpStatusCode status,
                                                                            WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        problem.setType(VALIDATION);
        Map<String, String> violations = new HashMap<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                violations.put(parameterName(result), error.getDefaultMessage());
            }
        }
        problem.setProperty("violations", violations);
        return validationResponse(problem);
    }

    private static String parameterName(ParameterValidationResult result) {
        MethodParameter parameter = result.getMethodParameter();
        return (parameter != null && parameter.getParameterName() != null)
                ? parameter.getParameterName() : "parameter";
    }

    private ResponseEntity<Object> validationResponse(ProblemDetail problem) {
        String correlationId = MDC.get(CorrelationId.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }
}
