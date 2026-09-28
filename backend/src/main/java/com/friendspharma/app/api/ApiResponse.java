package com.friendspharma.app.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * Standard API response envelope used by every endpoint.
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    private final boolean success;
    private final String message;
    private final T data;
    private final String errorCode;
    private final LocalDateTime timestamp = LocalDateTime.now();

    private ApiResponse(boolean success, String message, T data, String errorCode) {
        this.success   = success;
        this.message   = message;
        this.data      = data;
        this.errorCode = errorCode;
    }

    // ── Primary factory ────────────────────────────────────────────────────

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "Success", data, null);
    }

    public static <T> ApiResponse<T> success(T data, String message) {
        return new ApiResponse<>(true, message, data, null);
    }

    // ── Legacy aliases (ok / created / noContent) ─────────────────────────

    public static <T> ApiResponse<T> ok(T data) {
        return success(data);
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return success(data, message);
    }

    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(true, "Created successfully", data, null);
    }

    public static ApiResponse<Void> noContent(String message) {
        return new ApiResponse<>(true, message, null, null);
    }

    // ── Error factories ────────────────────────────────────────────────────

    public static <T> ApiResponse<T> error(String message, String errorCode) {
        return new ApiResponse<>(false, message, null, errorCode);
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, message, null, null);
    }

    public static <T> ApiResponse<T> error(String message, String errorCode, T data) {
        return new ApiResponse<>(false, message, data, errorCode);
    }
}
