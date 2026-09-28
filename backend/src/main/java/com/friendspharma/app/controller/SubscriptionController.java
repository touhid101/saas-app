package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.dto.response.SubscriptionResponse;
import com.friendspharma.app.service.impl.SubscriptionServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * A pharmacy's own subscription: what it costs, when it ends, how to renew it.
 *
 * <p>Every path here is on {@code TenantStatusFilter}'s open list, because these
 * are the screens an expired tenant needs most. Gating the renewal page behind a
 * live subscription would leave somebody locked out with no way back in.
 *
 * <p>They are still authenticated, and still branch-scoped — the branch comes off
 * the session, so nobody can renew or inspect a pharmacy that is not theirs.
 */
@RestController
@RequestMapping("/api/subscription")
@RequiredArgsConstructor
@Tag(name = "Subscription", description = "The signed-in pharmacy's own subscription and renewal")
public class SubscriptionController {

    private final SubscriptionServiceImpl svc;

    @GetMapping("/mine")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "This pharmacy's subscription, and whether it may still trade",
               description = "Reachable while expired: it is the screen that explains why "
                           + "everything else is not.")
    public ResponseEntity<ApiResponse<SubscriptionResponse.Mine>> mine() {
        return ResponseEntity.ok(ApiResponse.ok(svc.mine()));
    }

    @GetMapping("/plans")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The plans on sale")
    public ResponseEntity<ApiResponse<List<SubscriptionResponse.PlanDto>>> plans() {
        return ResponseEntity.ok(ApiResponse.ok(svc.plans()));
    }

    @PostMapping("/pay/renew")
    @PreAuthorize("hasAnyRole('ROLE01','ROLE02','ROLE04')")
    @Operation(summary = "Raise the next period's invoice",
               description = "Returns the invoice already outstanding rather than raising a "
                           + "second one, so clicking Renew twice owes one month, not two.")
    public ResponseEntity<ApiResponse<SubscriptionResponse.InvoiceDto>> renew(
            @RequestBody(required = false) Map<String, Object> body) {
        Long planId = body == null || body.get("planId") == null
                ? null : Long.valueOf(String.valueOf(body.get("planId")));
        return ResponseEntity.ok(ApiResponse.ok(
                "Invoice raised. Settle it to extend your subscription.",
                svc.raiseRenewal(svc.mine().getBranchId(), planId)));
    }
}
