package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.entity.TenantRegistration;
import com.friendspharma.app.service.impl.TenantOnboardingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where a pharmacy signs up. The only endpoint in the application that answers
 * without a token.
 *
 * <p>It creates nothing — one row in a queue, and a Super Admin decides. Anything
 * a stranger can bring into existence by filling in a form is something a
 * stranger can bring into existence a thousand of.
 *
 * <p>The response is deliberately thin: an id and a message. Echoing the
 * submission back would let this endpoint be used to confirm whether a username
 * exists, which is exactly the question an attacker asks first.
 */
@RestController
@RequestMapping("/api/public/register")
@RequiredArgsConstructor
@Tag(name = "Registration", description = "Pharmacy sign-up — the one endpoint that needs no token")
public class OnboardingController {

    private final TenantOnboardingService svc;

    @GetMapping("/options")
    @Operation(summary = "The plans and terms a new pharmacy can ask for",
               description = "Read-only and unauthenticated, like the sign-up form it feeds. "
                           + "Sale-facing fields only: what something costs is published, how the "
                           + "platform is run is not.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> options() {
        return ResponseEntity.ok(ApiResponse.ok(svc.publicOptions()));
    }

    @PostMapping
    @Operation(summary = "Apply to open a pharmacy account",
               description = "Creates a pending application. Nothing is live until a Super Admin "
                           + "approves it.")
    public ResponseEntity<ApiResponse<Map<String, Object>>> register(@RequestBody Map<String, String> body) {
        TenantRegistration reg = svc.register(
                body.get("pharmacyName"), body.get("contactName"), body.get("contactEmail"),
                body.get("contactPhone"), body.get("address"),
                body.get("adminUsername"), body.get("adminPassword"),
                body.get("preferredPlanCode"), months(body.get("preferredTermMonths")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("registrationId", reg.getPidRegistration());
        out.put("status", reg.getRegStatus());

        return ResponseEntity.ok(ApiResponse.ok(
                "Thank you. Your application has been received and is waiting for approval — "
                + "we will be in touch on " + reg.getContactPhone() + ".",
                out));
    }

    /**
     * The chosen term, or null.
     *
     * <p>Anything unparseable is treated as "not chosen" rather than as an error.
     * A preference is optional, and this endpoint takes its whole body from a
     * stranger — a malformed field here is not worth failing an application over.
     */
    private static Integer months(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Integer.valueOf(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
