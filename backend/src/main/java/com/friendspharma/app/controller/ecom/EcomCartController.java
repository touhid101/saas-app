package com.friendspharma.app.controller.ecom;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.dto.request.EcomCartRequests;
import com.friendspharma.app.dto.response.EcomCartResponses;
import com.friendspharma.app.service.impl.ecom.EcomCartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * The signed-in customer's carts — one per pharmacy. Every response is priced
 * and stock-checked at read time; nothing about money is accepted from the
 * client.
 */
@RestController
@RequestMapping("/api/ecom/cart")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ECOM_CUSTOMER')")
@Tag(name = "E-Commerce · Cart", description = "One cart per pharmacy, priced live on every read")
public class EcomCartController {

    private final EcomCartService carts;

    @Operation(summary = "My open carts, priced now")
    @GetMapping
    public ResponseEntity<ApiResponse<EcomCartResponses.Summary>> myCarts(
            @RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng) {
        return ResponseEntity.ok(ApiResponse.ok(carts.myCarts(lat, lng)));
    }

    @Operation(summary = "One cart of mine")
    @GetMapping("/{cartId}")
    public ResponseEntity<ApiResponse<EcomCartResponses.Cart>> get(
            @PathVariable Long cartId,
            @RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng) {
        return ResponseEntity.ok(ApiResponse.ok(carts.get(cartId, lat, lng)));
    }

    @Operation(summary = "Add a medicine from one pharmacy (opens that pharmacy's cart if there is none)")
    @PostMapping("/items")
    public ResponseEntity<ApiResponse<EcomCartResponses.Cart>> add(
            @Valid @RequestBody EcomCartRequests.AddLine req,
            @RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng) {
        return ResponseEntity.ok(ApiResponse.ok("Added to your cart", carts.add(req, lat, lng)));
    }

    @Operation(summary = "Change the quantity on a line (zero removes it)")
    @PutMapping("/{cartId}/items/{lineId}")
    public ResponseEntity<ApiResponse<EcomCartResponses.Cart>> setQty(
            @PathVariable Long cartId, @PathVariable Long lineId,
            @Valid @RequestBody EcomCartRequests.SetQty req,
            @RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng) {
        return ResponseEntity.ok(ApiResponse.ok(carts.setQty(cartId, lineId, req.getQty(), lat, lng)));
    }

    @Operation(summary = "Remove a line")
    @DeleteMapping("/{cartId}/items/{lineId}")
    public ResponseEntity<ApiResponse<EcomCartResponses.Cart>> remove(
            @PathVariable Long cartId, @PathVariable Long lineId,
            @RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng) {
        return ResponseEntity.ok(ApiResponse.ok("Removed", carts.removeLine(cartId, lineId, lat, lng)));
    }

    @Operation(summary = "A note for the pharmacy")
    @PatchMapping("/{cartId}/note")
    public ResponseEntity<ApiResponse<EcomCartResponses.Cart>> note(
            @PathVariable Long cartId, @Valid @RequestBody EcomCartRequests.CartNote req,
            @RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng) {
        return ResponseEntity.ok(ApiResponse.ok("Note saved", carts.setNote(cartId, req.getNote(), lat, lng)));
    }

    @Data
    public static class CouponRequest {
        @jakarta.validation.constraints.Size(max = 40) private String code;
    }

    @Operation(summary = "Put a coupon code on this cart",
               description = "Stored only if it is worth something here; the refusal says which rule it failed.")
    @PostMapping("/{cartId}/coupon")
    public ResponseEntity<ApiResponse<EcomCartResponses.Cart>> applyCoupon(
            @PathVariable Long cartId, @RequestBody CouponRequest req,
            @RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng) {
        return ResponseEntity.ok(ApiResponse.ok("Coupon applied", carts.applyCoupon(cartId, req.getCode(), lat, lng)));
    }

    @Operation(summary = "Take the coupon back off this cart")
    @DeleteMapping("/{cartId}/coupon")
    public ResponseEntity<ApiResponse<EcomCartResponses.Cart>> removeCoupon(
            @PathVariable Long cartId,
            @RequestParam(required = false) Double lat, @RequestParam(required = false) Double lng) {
        return ResponseEntity.ok(ApiResponse.ok("Coupon removed", carts.removeCoupon(cartId, lat, lng)));
    }

    @Operation(summary = "Empty and close a cart")
    @DeleteMapping("/{cartId}")
    public ResponseEntity<ApiResponse<Void>> abandon(@PathVariable Long cartId) {
        carts.abandon(cartId);
        return ResponseEntity.ok(ApiResponse.noContent("Cart emptied"));
    }
}
