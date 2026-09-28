package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.dto.request.LoginRequest;
import com.friendspharma.app.dto.request.RbacRequest;
import com.friendspharma.app.dto.request.UpdateProfileRequest;
import com.friendspharma.app.dto.response.AuthResponse;
import com.friendspharma.app.dto.response.UserResponse;
import com.friendspharma.app.service.interfaces.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Login, token refresh, logout and profile")
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Login — returns access + refresh token")
    @SecurityRequirements   // no auth needed
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Login successful", authService.login(request)));
    }

    @Operation(summary = "Refresh access token using a valid refresh token")
    @SecurityRequirements
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(@RequestBody Map<String, String> body) {
        String refreshToken = body.get("refreshToken");
        return ResponseEntity.ok(ApiResponse.ok("Token refreshed", authService.refresh(refreshToken)));
    }

    @Operation(summary = "Logout — revoke refresh token")
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@RequestBody(required = false) Map<String, String> body) {
        authService.logout(body != null ? body.get("refreshToken") : null);
        return ResponseEntity.ok(ApiResponse.noContent("Logged out successfully"));
    }

    @Operation(summary = "Get current authenticated user")
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> me() {
        return ResponseEntity.ok(ApiResponse.ok(authService.getCurrentUser()));
    }

    @Operation(summary = "Update current user's own profile (name, email, phone)")
    @PutMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> updateMe(
            @Valid @RequestBody UpdateProfileRequest request) {
        return ResponseEntity.ok(
                ApiResponse.ok("Profile updated successfully", authService.updateMyProfile(request)));
    }

    @Operation(summary = "Change current user's own password — current password must match, "
                       + "and the new password must differ from it")
    @PatchMapping("/me/password")
    public ResponseEntity<ApiResponse<Void>> changeMyPassword(
            @Valid @RequestBody RbacRequest.ChangePasswordRequest request) {
        authService.changeMyPassword(request);
        return ResponseEntity.ok(ApiResponse.noContent("Password changed successfully"));
    }

    @Operation(summary = "Upload avatar image for current user (max 2MB)")
    @PostMapping(path = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<UserResponse>> uploadAvatar(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.status(HttpStatus.OK)
                .body(ApiResponse.ok("Avatar updated successfully", authService.uploadAvatar(file)));
    }
}
