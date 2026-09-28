package com.friendspharma.app.exception.custom;

import org.springframework.http.HttpStatus;

/**
 * Raised when a hard delete is attempted on a product that already participates
 * in transactional history. The product must be deactivated instead.
 *
 * <p>Maps to HTTP 409 CONFLICT with errorCode {@code PRODUCT_HAS_TRANSACTIONS},
 * which the frontend uses to offer "Deactivate instead" in place of a bare error.
 */
public class ProductInUseException extends BusinessException {

    public ProductInUseException(String productName, String usageSummary) {
        super("'" + productName + "' cannot be deleted because transaction history exists ("
                        + usageSummary + "). Deactivate the product instead.",
                HttpStatus.CONFLICT, "PRODUCT_HAS_TRANSACTIONS");
    }
}
