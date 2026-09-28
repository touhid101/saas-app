package com.friendspharma.app.controller.ecom;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.response.EcomPointsResponses;
import com.friendspharma.app.service.impl.ecom.EcomCustomerPointsService;
import com.friendspharma.app.service.impl.ecom.EcomPointRuleAdminService;
import com.friendspharma.app.service.impl.ecom.EcomPointsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Loyalty points, staff side (MOD54, beside the coupons).
 *
 * <p>There is no default rule and nothing seeded: what a point is worth is a
 * decision, and until somebody makes it here, nothing earns and nothing
 * redeems.
 */
@RestController
@RequestMapping("/api/ecom/admin/points")
@RequiredArgsConstructor
@Tag(name = "E-Commerce · Points", description = "What a loyalty point is worth, and how one is earned")
public class EcomPointRuleAdminController {

    private final EcomPointRuleAdminService rules;
    private final EcomPointsService points;
    private final EcomCustomerPointsService statements;

    @Data
    public static class Adjustment {
        private BigDecimal points;
        private String reason;
    }

    @Operation(summary = "The points rules, active first")
    @GetMapping("/rules")
    public ResponseEntity<ApiResponse<PageResponse<EcomPointsResponses.Rule>>> list(
            @RequestParam(required = false) String active,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(rules.list(active, q, page, size))));
    }

    @Operation(summary = "One rule")
    @GetMapping("/rules/{id}")
    public ResponseEntity<ApiResponse<EcomPointsResponses.Rule>> detail(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(rules.detail(id)));
    }

    @Operation(summary = "Write a points rule — every number is yours to set")
    @PostMapping("/rules")
    public ResponseEntity<ApiResponse<EcomPointsResponses.Rule>> create(@RequestBody EcomPointsResponses.Form form) {
        return ResponseEntity.ok(ApiResponse.ok("Points rule created", rules.create(form)));
    }

    @Operation(summary = "Change a points rule")
    @PutMapping("/rules/{id}")
    public ResponseEntity<ApiResponse<EcomPointsResponses.Rule>> update(
            @PathVariable Long id, @RequestBody EcomPointsResponses.Form form) {
        return ResponseEntity.ok(ApiResponse.ok("Points rule saved", rules.update(id, form)));
    }

    @Operation(summary = "Start or stop a points rule",
               description = "Never deleted: points already earned under it point at it.")
    @PostMapping("/rules/{id}/active")
    public ResponseEntity<ApiResponse<EcomPointsResponses.Rule>> setActive(
            @PathVariable Long id, @RequestParam boolean on) {
        return ResponseEntity.ok(ApiResponse.ok(on ? "Rule started" : "Rule stopped", rules.setActive(id, on)));
    }

    @Operation(summary = "One customer's points, as a statement",
               description = "The ledger, newest first. The balance is its sum, not a figure anybody typed.")
    @GetMapping("/customers/{customerId}")
    public ResponseEntity<ApiResponse<EcomPointsResponses.Statement>> customer(
            @PathVariable Long customerId, @RequestParam(defaultValue = "25") int size) {
        rules.requireCustomerPointsView();
        return ResponseEntity.ok(ApiResponse.ok(statements.statementFor(customerId, size)));
    }

    @Operation(summary = "Move a customer's balance by hand, with a reason",
               description = "Written to the ledger like every other movement — the balance is never edited directly.")
    @PostMapping("/customers/{customerId}/adjust")
    public ResponseEntity<ApiResponse<Map<String, Object>>> adjust(
            @PathVariable Long customerId, @RequestBody Adjustment req) {
        rules.requireCustomerPointsAdjust();
        BigDecimal balance = points.adjust(customerId, req.getPoints(), req.getReason());
        return ResponseEntity.ok(ApiResponse.ok("Balance adjusted", Map.of("balance", balance)));
    }
}
