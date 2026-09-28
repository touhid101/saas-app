package com.friendspharma.app.controller.ecom;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.dto.request.EcomCustomerRequests;
import com.friendspharma.app.dto.response.EcomCustomerResponses;
import com.friendspharma.app.service.impl.ecom.EcomCustomerAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Customer sign-up and sign-in.
 *
 * <p>Register, login and refresh are open (see {@code SecurityConfig}); logout
 * and password change need a customer token. A staff token is worth nothing
 * on any of these — {@code EcomCustomerAuthenticationFilter} clears it — so
 * {@code hasRole('ECOM_CUSTOMER')} is the only principal that gets through.
 */
@RestController
@RequestMapping("/api/ecom/auth")
@RequiredArgsConstructor
@Tag(name = "E-Commerce · Customer auth", description = "Register, sign in, refresh, sign out, change password")
public class EcomCustomerAuthController {

    private final EcomCustomerAuthService auth;

    @Operation(summary = "Register a new online customer — pending approval, signed in at once")
    @SecurityRequirements
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<EcomCustomerResponses.Session>> register(
            @Valid @RequestBody EcomCustomerRequests.Register req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Registered. Your account is waiting for approval.", auth.register(req)));
    }

    @Operation(summary = "Sign in with mobile number and password")
    @SecurityRequirements
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<EcomCustomerResponses.Session>> login(
            @Valid @RequestBody EcomCustomerRequests.Login req) {
        return ResponseEntity.ok(ApiResponse.ok("Signed in", auth.login(req)));
    }

    @Operation(summary = "Exchange a refresh token for a new session (the old one is revoked)")
    @SecurityRequirements
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<EcomCustomerResponses.Session>> refresh(
            @Valid @RequestBody EcomCustomerRequests.Refresh req) {
        return ResponseEntity.ok(ApiResponse.ok("Session refreshed", auth.refresh(req.getRefreshToken())));
    }

    @Operation(summary = "Sign out — revokes the given refresh token, or every session when none is given")
    @PostMapping("/logout")
    @PreAuthorize("hasRole('ECOM_CUSTOMER')")
    public ResponseEntity<ApiResponse<Void>> logout(@RequestBody(required = false) Map<String, String> body) {
        auth.logout(body != null ? body.get("refreshToken") : null);
        return ResponseEntity.ok(ApiResponse.noContent("Signed out"));
    }

    @Operation(summary = "Change the signed-in customer's password")
    @PatchMapping("/password")
    @PreAuthorize("hasRole('ECOM_CUSTOMER')")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @Valid @RequestBody EcomCustomerRequests.ChangePassword req) {
        auth.changePassword(req);
        return ResponseEntity.ok(ApiResponse.noContent("Password changed"));
    }
}
