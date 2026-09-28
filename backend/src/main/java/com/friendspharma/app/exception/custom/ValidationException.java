package com.friendspharma.app.exception.custom;

import org.springframework.http.HttpStatus;

public class ValidationException extends BusinessException {
    public ValidationException(String message) {
        super(message, HttpStatus.BAD_REQUEST, "VALIDATION_ERROR");
    }
}
