package com.friendspharma.app.exception.custom;

import org.springframework.http.HttpStatus;

public class ResourceNotFoundException extends BusinessException {
    public ResourceNotFoundException(String resourceType, Object id) {
        super(
            String.format("%s not found with id: %s", resourceType, id),
            HttpStatus.NOT_FOUND,
            "RESOURCE_NOT_FOUND"
        );
    }
    public ResourceNotFoundException(String message) {
        super(message, HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND");
    }
}
