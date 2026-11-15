package com.flowops.security;

import com.flowops.domain.exception.ApiErrors;
import com.flowops.domain.CorrelationId;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.ObjectMapper;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    /**
     * Boot-managed Jackson mapper: it carries the ProblemDetail mixin that
     * flattens {@code properties} (e.g. correlationId) to the top level of the
     * JSON body, as required by RFC 7807.
     */
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, ObjectMapper objectMapper) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/token").permitAll()
                        .requestMatchers("/api/v1/auth/forgot-password", "/api/v1/auth/reset-password").permitAll()
                        // Swagger UI + OpenAPI docs — public for local/dev use
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").authenticated()
                        // All API endpoints require authentication;
                        // fine-grained authorization is handled by AuthorizationEvaluator in the service layer.
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(problemEntryPoint(401, "authentication"))
                        .accessDeniedHandler(problemDeniedHandler(403, "forbidden")))
                .addFilterBefore(jwtAuthFilter,
                        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Emits RFC 7807 ProblemDetail bodies (with correlationId) for 401
     * instead of the default empty-body responses.
     */
    private AuthenticationEntryPoint problemEntryPoint(int status, String name) {
        return (request, response, e) -> writeProblem(response, status, name, "Authentication required");
    }

    private AccessDeniedHandler problemDeniedHandler(int status, String name) {
        return (request, response, e) -> writeProblem(response, status, name, "Access denied");
    }

    private void writeProblem(HttpServletResponse response, int status, String name, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status), detail);
        problem.setType(ApiErrors.type(name));
        String correlationId = MDC.get(CorrelationId.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
            response.setHeader(CorrelationId.HEADER, correlationId);
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(problem));
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
