package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.dto.response.PasswordAdminResponse.*;
import com.friendspharma.app.service.impl.PasswordAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * [V71] Password administration across every pharmacy on the installation.
 *
 * <p>Two gates, not one. {@code hasRole('ROLE01')} here stops the request at the
 * security filter, and {@code isPlatformOperator()} inside the service stops it
 * again with the stricter test — ROLE01 is held by the administrator of every
 * customer pharmacy too, and this controller reads across all of them. The outer
 * gate is the cheap one; the inner gate is the one that is actually correct.
 *
 * <p>No endpoint here returns a stored password, because none can: they are
 * BCrypt hashes and the original is not recoverable from them. The reset
 * endpoint returns a newly generated password once, in its response body and
 * nowhere else.
 */
@RestController
@RequestMapping("/api/admin/passwords")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ROLE01')")
@Tag(name = "Password administration",
     description = "Reset a user's password and read the reset trail — platform operator only")
public class PasswordAdminController {

    private final PasswordAdminService svc;

    @GetMapping("/accounts")
    @Operation(summary = "Every account, with the state of its password",
               description = "Reports a status word — ACTIVE, TEMPORARY, EXPIRED, WEAK or UNSET — "
                           + "in place of the password itself, which is stored as a one-way hash "
                           + "and cannot be read back by this application or any other.")
    public ResponseEntity<ApiResponse<AccountList>> accounts() {
        return ResponseEntity.ok(ApiResponse.ok(svc.list()));
    }

    @PostMapping("/{pidUser}/reset")
    @Operation(summary = "Issue a temporary password",
               description = "Generates a new random password, stores only its hash, and returns "
                           + "it once so it can be handed to the user. The account must then set "
                           + "its own password before it can do anything else.")
    public ResponseEntity<ApiResponse<ResetResult>> reset(
            @PathVariable Long pidUser, HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Temporary password issued.", svc.reset(pidUser, clientIp(request))));
    }

    @GetMapping("/audit")
    @Operation(summary = "The reset trail",
               description = "Newest first. Refusals are listed alongside resets: repeated "
                           + "refusals against one account are worth being able to see.")
    public ResponseEntity<ApiResponse<List<AuditEntry>>> audit(
            @RequestParam(required = false) Long pidUser,
            @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(ApiResponse.ok(svc.trail(pidUser, limit)));
    }

    /**
     * The caller's address for the audit row.
     *
     * <p>X-Forwarded-For is read first because the application sits behind a
     * proxy in production, where the socket address is the proxy for every
     * request and would make every audit row identical. The header is
     * client-supplied and therefore not trustworthy on its own — it is recorded
     * as a lead to follow, not as proof of origin.
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            String first = (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
            if (!first.isEmpty()) return first.length() > 45 ? first.substring(0, 45) : first;
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.length() <= 45 ? remote : remote.substring(0, 45);
    }
}
