package com.friendspharma.app.controller.ecom;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.api.PageResponse;
import com.friendspharma.app.dto.response.EcomCouponResponses;
import com.friendspharma.app.service.impl.ecom.EcomCouponAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * E-Commerce Promotions (MOD54): the coupon codes customers type at checkout.
 *
 * <p>Staff JWT, under the {@code /api/ecom/admin/} prefix like the rest of the
 * staff side. Who may write which coupon is decided in the service — a pharmacy
 * for its own branch, the platform for everybody — because the screen is not
 * the only way in.
 */
@RestController
@RequestMapping("/api/ecom/admin/coupons")
@RequiredArgsConstructor
@Tag(name = "E-Commerce · Promotions", description = "Coupon codes: who may use them, where, and for how long")
public class EcomCouponAdminController {

    private final EcomCouponAdminService coupons;

    @Operation(summary = "Coupons, newest first; active ones at the top")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<EcomCouponResponses.Row>>> list(
            @RequestParam(required = false) String active,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return ResponseEntity.ok(ApiResponse.ok(PageResponse.from(coupons.list(active, q, page, size))));
    }

    @Operation(summary = "One coupon, with the pharmacies that honour it")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<EcomCouponResponses.Detail>> detail(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(coupons.detail(id)));
    }

    @Operation(summary = "Write a new coupon")
    @PostMapping
    public ResponseEntity<ApiResponse<EcomCouponResponses.Row>> create(@RequestBody EcomCouponResponses.Form form) {
        return ResponseEntity.ok(ApiResponse.ok("Coupon created", coupons.create(form)));
    }

    @Operation(summary = "Change a coupon's rules — the code itself never changes")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<EcomCouponResponses.Row>> update(
            @PathVariable Long id, @RequestBody EcomCouponResponses.Form form) {
        return ResponseEntity.ok(ApiResponse.ok("Coupon saved", coupons.update(id, form)));
    }

    @Operation(summary = "Start or stop a coupon",
               description = "Never deleted: orders that were paid with it point at it.")
    @PostMapping("/{id}/active")
    public ResponseEntity<ApiResponse<EcomCouponResponses.Row>> setActive(
            @PathVariable Long id, @RequestParam boolean on) {
        return ResponseEntity.ok(ApiResponse.ok(on ? "Coupon started" : "Coupon stopped", coupons.setActive(id, on)));
    }
}
