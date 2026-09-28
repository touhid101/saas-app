package com.friendspharma.app.exception.custom;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Base exception for all domain/business rule violations.
 * All service-layer exceptions should extend this.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final HttpStatus httpStatus;
    private final String errorCode;

    public BusinessException(String message, HttpStatus httpStatus, String errorCode) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public BusinessException(String message, HttpStatus httpStatus) {
        this(message, httpStatus, httpStatus.name());
    }

    public BusinessException(String message) {
        this(message, HttpStatus.BAD_REQUEST);
    }
}
