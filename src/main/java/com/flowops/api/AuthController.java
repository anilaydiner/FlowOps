package com.flowops.api;

import com.flowops.api.dto.AuthRequest;
import com.flowops.api.dto.AuthResponse;
import com.flowops.domain.User;
import com.flowops.security.JwtService;
import com.flowops.application.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserService userService;
    private final JwtService jwtService;

    public AuthController(UserService userService, JwtService jwtService) {
        this.userService = userService;
        this.jwtService = jwtService;
    }

    @PostMapping("/token")
    public ResponseEntity<AuthResponse> token(@Valid @RequestBody AuthRequest request) {
        User user = userService.authenticate(request.username(), request.password());
        String token = jwtService.issue(user);
        return ResponseEntity.ok(AuthResponse.bearer(token, jwtService.getExpirationSeconds()));
    }
}
