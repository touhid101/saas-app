package com.friendspharma.app.exception.custom;

import org.springframework.http.HttpStatus;

public class ExpiredBatchException extends BusinessException {
    public ExpiredBatchException(String productName, String batchNo, String expiryDate) {
        super(
            String.format("Cannot sell expired batch: '%s' batch %s expired on %s.", productName, batchNo, expiryDate),
            HttpStatus.UNPROCESSABLE_ENTITY,
            "EXPIRED_BATCH"
        );
    }
}
