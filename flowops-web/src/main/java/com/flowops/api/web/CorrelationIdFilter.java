package com.flowops.api.web;

import com.flowops.domain.CorrelationId;
import com.flowops.application.audit.AuditRequestOrigin;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ensures every request carries a correlation ID: - uses inbound X-Correlation-Id header
 * when present (sanitized), - otherwise generates a UUID, - stores it in MDC for
 * structured logging, - echoes it back in the response header.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdFilter.class);

    private static final int MAX_LENGTH = 64;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = sanitize(request.getHeader(CorrelationId.HEADER));
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        MDC.put(CorrelationId.MDC_KEY, id);
        response.setHeader(CorrelationId.HEADER, id);
        log.debug("Processing {} {} with correlationId {}", request.getMethod(), request.getRequestURI(), id);
        try (var origin = AuditRequestOrigin.open(id, request.getRemoteAddr(), request.getHeader("User-Agent"))) {
            chain.doFilter(request, response);
        }
        finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }

    private String sanitize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.strip().replaceAll("[^A-Za-z0-9._-]", "-");
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH) : cleaned;
    }

}
