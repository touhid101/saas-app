package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.dto.response.SalesPerformanceResponse;
import com.friendspharma.app.service.impl.SalesPerformanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Sales performance for the caller's branch.
 *
 * <p>No role is named here. Reading is the View right on <b>Sales Performance</b>
 * in RBAC and setting a target is the Edit right, both checked in the service —
 * so who can do what changes by a tick rather than an edit.
 */
@RestController
@RequestMapping("/api/sales-performance")
@RequiredArgsConstructor
@Tag(name = "Sales Performance", description = "What each person sold this month, against their target")
public class SalesPerformanceController {

    private final SalesPerformanceService svc;

    @GetMapping
    @Operation(summary = "Performance for one month",
               description = "Defaults to the current month. Attribution is the user who raised "
                           + "each invoice. Target, achievement and commission are null for anyone "
                           + "without a target set — they are not guessed.")
    public ResponseEntity<ApiResponse<SalesPerformanceResponse>> forMonth(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate month) {
        return ResponseEntity.ok(ApiResponse.ok(svc.forMonth(month)));
    }

    @PutMapping("/target")
    @Operation(summary = "Set or clear one person's target for a month",
               description = "An amount of zero or null clears the target, so \"not set\" stays "
                           + "distinguishable from a target of nothing. Needs the Edit right.")
    public ResponseEntity<ApiResponse<SalesPerformanceResponse>> setTarget(
            @RequestBody TargetRequest req) {
        svc.setTarget(req.getUserId(), req.getMonth(), req.getTargetAmount(), req.getCommissionPct());
        // The whole month back, so the screen never has to recompute totals and
        // achievement for itself and reach a different answer from the server.
        return ResponseEntity.ok(ApiResponse.ok("Target saved", svc.forMonth(req.getMonth())));
    }

    @Data
    public static class TargetRequest {
        @NotNull
        private Long userId;

        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
        private LocalDate month;

        /** Null or zero clears the target. */
        private BigDecimal targetAmount;

        /** Percent, not a fraction: 2.5 means 2.5%. */
        private BigDecimal commissionPct;
    }
}
