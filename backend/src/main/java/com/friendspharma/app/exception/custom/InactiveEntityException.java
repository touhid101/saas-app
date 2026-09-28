package com.friendspharma.app.exception.custom;

import org.springframework.http.HttpStatus;

public class InactiveEntityException extends BusinessException {
    public InactiveEntityException(String entityType, String name) {
        super(
            String.format("Cannot use inactive %s: '%s'. Please activate it first.", entityType, name),
            HttpStatus.UNPROCESSABLE_ENTITY,
            "INACTIVE_ENTITY"
        );
    }
}
