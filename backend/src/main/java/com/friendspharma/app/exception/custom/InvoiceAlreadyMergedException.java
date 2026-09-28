package com.friendspharma.app.exception.custom;

import org.springframework.http.HttpStatus;

public class InvoiceAlreadyMergedException extends BusinessException {
    public InvoiceAlreadyMergedException(String invoiceNo) {
        super(
            String.format("Cannot merge invoice %s: it has already been merged into another invoice.", invoiceNo),
            HttpStatus.UNPROCESSABLE_ENTITY,
            "INVOICE_ALREADY_MERGED"
        );
    }
}
