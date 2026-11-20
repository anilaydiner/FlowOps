package com.flowops.api;

import com.flowops.api.dto.AuthRequest;
import com.flowops.api.dto.AuthResponse;
import com.flowops.api.dto.ForgotPasswordRequest;
import com.flowops.api.dto.ResetPasswordRequest;
import com.flowops.application.identity.PasswordResetService;
import com.flowops.application.identity.session.AuthenticationService;
import com.flowops.application.identity.session.TokenPair;
import com.flowops.api.dto.RefreshTokenRequest;
import com.flowops.security.UserPrincipal;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthenticationService authentication;

    private final PasswordResetService passwordResetService;

    public AuthController(AuthenticationService authentication, PasswordResetService passwordResetService) {
        this.authentication = authentication;
        this.passwordResetService = passwordResetService;
    }

    @PostMapping("/token")
    public ResponseEntity<AuthResponse> token(@Valid @RequestBody AuthRequest request) {
        return tokens(authentication.login(request.username(), request.password()));
    }

    @PostMapping("/refresh")
    @Operation(summary = "Rotate a refresh token and issue a new access/refresh pair")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return tokens(authentication.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    @Operation(summary = "Revoke the current refresh session; issued access tokens expire naturally")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal UserPrincipal principal,
            @Valid @RequestBody(required = false) RefreshTokenRequest request) {
        authentication.logout(principal != null ? principal.sessionId() : null,
                request != null ? request.refreshToken() : null);
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/logout-all")
    @Operation(summary = "Revoke every refresh session for the authenticated user")
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal UserPrincipal principal) {
        authentication.logoutAll(principal.id());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }

    private ResponseEntity<AuthResponse> tokens(TokenPair pair) {
        return ResponseEntity.ok()
            .cacheControl(CacheControl.noStore())
            .header(HttpHeaders.PRAGMA, "no-cache")
            .body(AuthResponse.from(pair));
    }

    /**
     * Request a password-reset email.
     *
     * <p>
     * Always returns {@code 202 Accepted} with an identical body, whether or not the
     * address is registered — this prevents account enumeration. When the address is
     * known, a reset link is enqueued for delivery.
     */
    @Operation(summary = "Request a password-reset email (always 202)")
    @ApiResponses(@ApiResponse(responseCode = "202", description = "Accepted — a reset email may have been queued"))
    @PostMapping("/forgot-password")
    public ResponseEntity<Map<String, String>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.requestReset(request.email());
        // Deliberately uniform response — never reveals whether the account exists.
        return ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(Map.of("message", "If that address is registered, a password-reset link is on its way."));
    }

    /**
     * Reset the password using a token from the reset link.
     *
     * <p>
     * The token is single-use and short-lived. All failure modes (unknown, expired,
     * already used) return the same {@code 400} so the client cannot distinguish them.
     */
    @Operation(summary = "Set a new password using a reset token")
    @ApiResponses({ @ApiResponse(responseCode = "200", description = "Password updated"),
            @ApiResponse(responseCode = "400", description = "Invalid/expired token or weak password") })
    @PostMapping("/reset-password")
    public ResponseEntity<Map<String, String>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.resetPassword(request.token(), request.newPassword());
        return ResponseEntity.ok(Map.of("message", "Password updated successfully."));
    }

}
