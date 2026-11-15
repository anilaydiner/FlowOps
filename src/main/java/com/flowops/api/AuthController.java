package com.flowops.api;

import com.flowops.api.dto.AuthRequest;
import com.flowops.api.dto.AuthResponse;
import com.flowops.api.dto.ForgotPasswordRequest;
import com.flowops.api.dto.ResetPasswordRequest;
import com.flowops.domain.User;
import com.flowops.security.JwtService;
import com.flowops.application.service.PasswordResetService;
import com.flowops.application.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final UserService userService;
    private final PasswordResetService passwordResetService;
    private final JwtService jwtService;

    public AuthController(UserService userService,
                          PasswordResetService passwordResetService,
                          JwtService jwtService) {
        this.userService = userService;
        this.passwordResetService = passwordResetService;
        this.jwtService = jwtService;
    }

    @PostMapping("/token")
    public ResponseEntity<AuthResponse> token(@Valid @RequestBody AuthRequest request) {
        User user = userService.authenticate(request.username(), request.password());
        String token = jwtService.issue(user);
        return ResponseEntity.ok(AuthResponse.bearer(token, jwtService.getExpirationSeconds()));
    }

    /**
     * Request a password-reset email.
     *
     * <p>Always returns {@code 202 Accepted} with an identical body, whether or
     * not the address is registered — this prevents account enumeration. When
     * the address is known, a reset link is enqueued for delivery.
     */
    @Operation(summary = "Request a password-reset email (always 202)")
    @ApiResponses(@ApiResponse(responseCode = "202", description = "Accepted — a reset email may have been queued"))
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.requestReset(request.email());
        // Deliberately uniform response — never reveals whether the account exists.
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of(
                "message", "If that address is registered, a password-reset link is on its way."
        ));
    }

    /**
     * Reset the password using a token from the reset link.
     *
     * <p>The token is single-use and short-lived. All failure modes (unknown,
     * expired, already used) return the same {@code 400} so the client cannot
     * distinguish them.
     */
    @Operation(summary = "Set a new password using a reset token")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Password updated"),
            @ApiResponse(responseCode = "400", description = "Invalid/expired token or weak password")
    })
    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.ok(Map.of("message", "Password updated successfully."));
    }
}
