package com.expensetracker.common;

import org.springframework.http.HttpStatus;

import java.util.List;

/** Base exception rendered by {@link GlobalExceptionHandler} into the standard error envelope. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final List<String> details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public ApiException(HttpStatus status, String code, String message, List<String> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public List<String> getDetails() {
        return details;
    }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", what + " not found");
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNPROCESSABLE", message);
    }
}
