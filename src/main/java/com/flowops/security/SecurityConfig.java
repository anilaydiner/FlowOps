package com.flowops.security;

import com.flowops.domain.UserRepository;
import com.flowops.infra.CorrelationId;
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
import java.net.URI;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    public static final String ERROR_BASE = "urn:flowops:error:";

    private final JwtAuthFilter jwtAuthFilter;
    private final UserRepository users;

    /**
     * Boot-managed Jackson mapper: it carries the ProblemDetail mixin that
     * flattens {@code properties} (e.g. correlationId) to the top level of the
     * JSON body, as required by RFC 7807.
     */
    private final ObjectMapper objectMapper;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, ObjectMapper objectMapper, UserRepository users) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.objectMapper = objectMapper;
        this.users = users;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/auth/token").permitAll()
                        // Swagger UI + OpenAPI docs — public for local/dev use
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                        .requestMatchers("/actuator/**").access(new PermissionAuthorizationManager(users))
                        // User management — permission-guarded (coarse layer, PLAN §7)
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/users").access(new PermissionAuthorizationManager(users))
                        .requestMatchers(org.springframework.http.HttpMethod.PUT, "/api/v1/users/*").access(new PermissionAuthorizationManager(users))
                        .requestMatchers(org.springframework.http.HttpMethod.DELETE, "/api/v1/users/*").access(new PermissionAuthorizationManager(users))
                        .requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/users").access(new PermissionAuthorizationManager(users))
                        // Role management — permission-guarded
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/roles").access(new PermissionAuthorizationManager(users))
                        .requestMatchers(org.springframework.http.HttpMethod.PUT, "/api/v1/roles/*").access(new PermissionAuthorizationManager(users))
                        .requestMatchers(org.springframework.http.HttpMethod.DELETE, "/api/v1/roles/*").access(new PermissionAuthorizationManager(users))
                        // Request types — permission-guarded
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/request-types").access(new PermissionAuthorizationManager(users))
                        .requestMatchers(org.springframework.http.HttpMethod.PUT, "/api/v1/request-types/*").access(new PermissionAuthorizationManager(users))
                        // Workflows — permission-guarded
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/workflows").access(new PermissionAuthorizationManager(users))
                        .requestMatchers(org.springframework.http.HttpMethod.PUT, "/api/v1/workflows/*").access(new PermissionAuthorizationManager(users))
                        .requestMatchers(org.springframework.http.HttpMethod.DELETE, "/api/v1/workflows/*").access(new PermissionAuthorizationManager(users))
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
        problem.setType(URI.create(ERROR_BASE + name));
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
