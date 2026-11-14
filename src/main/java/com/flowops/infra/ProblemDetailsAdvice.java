package com.flowops.infra;

import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.net.URI;

/**
 * Enriches every ProblemDetail response written through the MVC message
 * converters with the request's correlation ID.
 * (Validation failures are handled by {@link FlowOpsExceptionHandler}, which
 * adds them directly since ResponseEntity-wrapped bodies bypass this advice.)
 */
@RestControllerAdvice
public class ProblemDetailsAdvice implements ResponseBodyAdvice<ProblemDetail> {

    private static final URI BASE = URI.create("urn:flowops:error:");

    @Override
    public boolean supports(MethodParameter methodParameter, Class<? extends HttpMessageConverter<?>> converterType) {
        return ProblemDetail.class.isAssignableFrom(methodParameter.getParameterType());
    }

    @Override
    public ProblemDetail beforeBodyWrite(ProblemDetail body,
                                         MethodParameter returnType,
                                         MediaType mediaType,
                                         Class<? extends HttpMessageConverter<?>> converterType,
                                         ServerHttpRequest request,
                                         ServerHttpResponse response) {
        String correlationId = MDC.get(CorrelationId.MDC_KEY);
        if (correlationId != null) {
            body.setProperty("correlationId", correlationId);
        }
        if (body.getType() == null) {
            body.setType(BASE);
        }
        return body;
    }
}
