package com.friendspharma.app.controller.ecom;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.dto.response.EcomOrderResponses;
import com.friendspharma.app.dto.response.EcomTrackingResponses;
import com.friendspharma.app.service.impl.ecom.EcomCheckoutService;
import com.friendspharma.app.service.impl.ecom.EcomOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Checkout and the customer's own orders.
 *
 * <p>The checkout request names a cart and an address. It carries no prices,
 * no quantities and no products: those are the cart's, and the cart is priced
 * again server-side inside the transaction that places the order.
 */
@RestController
@RequestMapping("/api/ecom/orders")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ECOM_CUSTOMER')")
@Tag(name = "E-Commerce · Orders", description = "Place an order from a cart, then follow it")
public class EcomOrderController {

    private final EcomCheckoutService checkout;
    private final EcomOrderService orders;
    /** Where it is while it is out, and the evidence once it arrives. [Phase 10] */
    private final com.friendspharma.app.service.impl.ecom.EcomDeliveryTrackingService delivery;

    @Data
    public static class PlaceOrder {
        @NotNull(message = "Which cart?") private Long cartId;
        @NotNull(message = "Where should it go?") private Long addressId;
        @Size(max = 300) private String note;
        /** Loyalty points to spend on this order. What they are worth is the server's to say. */
        private Integer redeemPoints;
    }

    @Data
    public static class Cancel {
        @Size(max = 300) private String reason;
    }

    @Operation(summary = "Place one cart as an order at its pharmacy — prices and stock are confirmed again here")
    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> place(@Valid @RequestBody PlaceOrder req) {
        EcomCheckoutService.EcomOrderView v = checkout.placeOrder(
                req.getCartId(), req.getAddressId(), req.getNote(), req.getRedeemPoints());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                "Order placed",
                Map.of("orderNo", v.order().getOrderNo(),
                       "status", v.order().getEcomStatus(),
                       "branchName", v.branchName(),
                       "grandTotal", v.order().getGrandTotal(),
                       "pointsRedeemed", v.order().getPointsRedeemed(),
                       "pointsValue", v.order().getPointsValue(),
                       "lineCount", v.items().size())));
    }

    @Operation(summary = "My orders, newest first")
    @GetMapping
    public ResponseEntity<ApiResponse<EcomOrderResponses.Page>> mine(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(orders.myOrders(page, size)));
    }

    @Operation(summary = "One of my orders, with its history")
    @GetMapping("/{orderNo}")
    public ResponseEntity<ApiResponse<EcomOrderResponses.Order>> get(@PathVariable String orderNo) {
        return ResponseEntity.ok(ApiResponse.ok(orders.get(orderNo)));
    }

    @Operation(summary = "Where my order is, and what happened at the door",
               description = "The latest position while it is out — one point, never a trail — and the "
                           + "proof of delivery once it has been handed over.")
    @GetMapping("/{orderNo}/tracking")
    public ResponseEntity<ApiResponse<EcomTrackingResponses.Tracking>> tracking(@PathVariable String orderNo) {
        return ResponseEntity.ok(ApiResponse.ok(delivery.forCustomer(orderNo)));
    }

    @Operation(summary = "Call off an order the pharmacy has not confirmed yet")
    @PostMapping("/{orderNo}/cancel")
    public ResponseEntity<ApiResponse<EcomOrderResponses.Order>> cancel(
            @PathVariable String orderNo, @RequestBody(required = false) Cancel req) {
        return ResponseEntity.ok(ApiResponse.ok("Order cancelled", orders.cancel(orderNo, req == null ? null : req.getReason())));
    }
}
