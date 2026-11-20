package com.flowops.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Validates the Bearer token on each request and populates the SecurityContext with a
 * {@link UserPrincipal}. Invalid/missing tokens leave the context unauthenticated (the
 * entry point then renders a 401 ProblemDetail). Access-token validation is stateless:
 * refresh revocation does not invalidate already-issued JWTs, which remain valid until
 * their short expiration.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            Optional<UserPrincipal> principal = jwtService.parse(header.substring(7).strip());
            principal.ifPresentOrElse(p -> {
                // Build one ROLE_* authority per role code
                List<SimpleGrantedAuthority> authorities = p.roles()
                    .stream()
                    .map(code -> new SimpleGrantedAuthority("ROLE_" + code))
                    .toList();
                var auth = new UsernamePasswordAuthenticationToken(p, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(auth);
            }, () -> log.debug("Rejected invalid bearer token for {} {}", request.getMethod(),
                    request.getRequestURI()));
        }
        chain.doFilter(request, response);
    }

}
