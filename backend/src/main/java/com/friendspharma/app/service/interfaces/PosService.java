package com.friendspharma.app.service.interfaces;

import com.friendspharma.app.dto.request.PosRequest;
import com.friendspharma.app.dto.response.PosResponse;

import java.util.List;

public interface PosService {

    /** Barcode / product-code lookup */
    PosResponse.BarcodeProductDto lookupByBarcode(String barcode, Long orgId);

    /** Get or create an OPEN cart for the current user */
    PosResponse.CartDto getOrCreateCart(Long orgId, Long branchId, Long userId);

    /** Add / update an item in the active cart */
    PosResponse.CartDto addItem(Long cartId, PosRequest.AddItemRequest req);

    /** Update quantity or discount for a cart line */
    PosResponse.CartDto updateItem(Long cartId, Long cartItemId, PosRequest.UpdateItemRequest req);

    /** Remove a line from the cart */
    PosResponse.CartDto removeItem(Long cartId, Long cartItemId);

    /** Clear all lines from the cart */
    PosResponse.CartDto clearCart(Long cartId);

    /** Park cart for later recall */
    PosResponse.CartDto holdCart(Long cartId, PosRequest.HoldCartRequest req);

    /** Retrieve a held cart by reference token */
    PosResponse.CartDto recallCart(PosRequest.RecallCartRequest req, Long userId);

    /** List all held carts for a branch */
    List<PosResponse.CartDto> listHeldCarts(Long branchId);

    /** Assign customer to cart */
    PosResponse.CartDto assignCustomer(Long cartId, Long customerId);

    /** Finalise cart → create POS_TRANSACTION_MST + call FEFO proc */
    PosResponse.ReceiptDto finalise(PosRequest.FinaliseCartRequest req, Long userId);

    /** Quick sale (no cart hold/recall — single step) */
    PosResponse.ReceiptDto quickSale(PosRequest.QuickSaleRequest req, Long userId);

    /** Get receipt for an already-finalised transaction */
    PosResponse.ReceiptDto getReceipt(Long transactionId);
}
