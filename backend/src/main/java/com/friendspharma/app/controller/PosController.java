package com.friendspharma.app.controller;

import com.friendspharma.app.api.ApiResponse;
import com.friendspharma.app.dto.request.PosRequest;
import com.friendspharma.app.dto.response.PosResponse;
import com.friendspharma.app.security.SecurityUtils;
import com.friendspharma.app.service.interfaces.PosService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/pos")
@RequiredArgsConstructor
@Tag(name = "POS", description = "Point-of-Sale: barcode lookup, cart management, quick billing, receipt")
public class PosController {

    private final PosService posService;

    // ── Barcode / product lookup ───────────────────────────────────────────

    @GetMapping("/barcode/{barcode}")
    @Operation(summary = "Look up product by barcode or product code")
    public ApiResponse<PosResponse.BarcodeProductDto> barcodeLookup(
            @PathVariable String barcode,
            @RequestParam(required = false) Long orgId) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        return ApiResponse.success(posService.lookupByBarcode(barcode, orgId));
    }

    // ── Cart ──────────────────────────────────────────────────────────────

    @GetMapping("/cart")
    @Operation(summary = "Get or create an OPEN cart for the current user")
    public ApiResponse<PosResponse.CartDto> getOrCreateCart(
            @RequestParam(required = false) Long orgId,
            @RequestParam Long branchId) {
        // The organisation is whoever is signed in, never what was asked for.
        orgId = SecurityUtils.getCurrentOrgId();
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.success(posService.getOrCreateCart(orgId, branchId, userId));
    }

    @PostMapping("/cart/{cartId}/items")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a product to the active cart")
    public ApiResponse<PosResponse.CartDto> addItem(
            @PathVariable Long cartId,
            @Valid @RequestBody PosRequest.AddItemRequest req) {
        return ApiResponse.success(posService.addItem(cartId, req));
    }

    @PutMapping("/cart/{cartId}/items/{cartItemId}")
    @Operation(summary = "Update quantity / discount for a cart line")
    public ApiResponse<PosResponse.CartDto> updateItem(
            @PathVariable Long cartId,
            @PathVariable Long cartItemId,
            @Valid @RequestBody PosRequest.UpdateItemRequest req) {
        return ApiResponse.success(posService.updateItem(cartId, cartItemId, req));
    }

    @DeleteMapping("/cart/{cartId}/items/{cartItemId}")
    @Operation(summary = "Remove a line from the cart")
    public ApiResponse<PosResponse.CartDto> removeItem(
            @PathVariable Long cartId,
            @PathVariable Long cartItemId) {
        return ApiResponse.success(posService.removeItem(cartId, cartItemId));
    }

    @DeleteMapping("/cart/{cartId}/clear")
    @Operation(summary = "Clear all items from the cart")
    public ApiResponse<PosResponse.CartDto> clearCart(@PathVariable Long cartId) {
        return ApiResponse.success(posService.clearCart(cartId));
    }

    @PutMapping("/cart/{cartId}/customer/{customerId}")
    @Operation(summary = "Assign customer to a cart")
    public ApiResponse<PosResponse.CartDto> assignCustomer(
            @PathVariable Long cartId,
            @PathVariable Long customerId) {
        return ApiResponse.success(posService.assignCustomer(cartId, customerId));
    }

    // ── Hold / Recall ─────────────────────────────────────────────────────

    @PostMapping("/cart/{cartId}/hold")
    @Operation(summary = "Park (hold) a cart for later recall")
    public ApiResponse<PosResponse.CartDto> holdCart(
            @PathVariable Long cartId,
            @RequestBody(required = false) PosRequest.HoldCartRequest req) {
        if (req == null) req = new PosRequest.HoldCartRequest();
        return ApiResponse.success(posService.holdCart(cartId, req));
    }

    @PostMapping("/cart/recall")
    @Operation(summary = "Recall a held cart by its reference token")
    public ApiResponse<PosResponse.CartDto> recallCart(
            @Valid @RequestBody PosRequest.RecallCartRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.success(posService.recallCart(req, userId));
    }

    @GetMapping("/cart/held")
    @Operation(summary = "List all held carts for a branch")
    public ApiResponse<List<PosResponse.CartDto>> listHeld(@RequestParam Long branchId) {
        return ApiResponse.success(posService.listHeldCarts(branchId));
    }

    // ── Finalise / Quick Sale ─────────────────────────────────────────────

    @PostMapping("/finalise")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Finalise cart — calls FEFO proc and creates sales invoice")
    public ApiResponse<PosResponse.ReceiptDto> finalise(
            @Valid @RequestBody PosRequest.FinaliseCartRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.success(posService.finalise(req, userId));
    }

    @PostMapping("/quick-sale")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Quick sale (single-step — no hold/recall needed)")
    public ApiResponse<PosResponse.ReceiptDto> quickSale(
            @Valid @RequestBody PosRequest.QuickSaleRequest req) {
        Long userId = SecurityUtils.getCurrentUserId();
        return ApiResponse.success(posService.quickSale(req, userId));
    }

    // ── Receipt ───────────────────────────────────────────────────────────

    @GetMapping("/receipt/{transactionId}")
    @Operation(summary = "Get receipt for a finalised transaction")
    public ApiResponse<PosResponse.ReceiptDto> receipt(@PathVariable Long transactionId) {
        return ApiResponse.success(posService.getReceipt(transactionId));
    }
}
