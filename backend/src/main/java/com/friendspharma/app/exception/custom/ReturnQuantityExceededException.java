package com.friendspharma.app.exception.custom;

import org.springframework.http.HttpStatus;

public class ReturnQuantityExceededException extends BusinessException {
    public ReturnQuantityExceededException(String productName, int returnQty, int maxAllowed) {
        super(
            String.format("Return quantity %d for '%s' exceeds the maximum allowed return of %d.",
                returnQty, productName, maxAllowed),
            HttpStatus.UNPROCESSABLE_ENTITY,
            "RETURN_QTY_EXCEEDED"
        );
    }
}
