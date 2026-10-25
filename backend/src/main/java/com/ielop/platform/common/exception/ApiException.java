package com.ielop.platform.common.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * An error with its API code and HTTP status (spec sections 8.4 and 8.7). Feature
 * packages extend or throw it; {@link GlobalExceptionHandler} renders it as {@link ApiError}.
 */
public class ApiException extends RuntimeException {

    private final String code;
    private final HttpStatus status;
    private final Map<String, String> fieldErrors;

    public ApiException(String code, HttpStatus status, String message) {
        this(code, status, message, null, null);
    }

    public ApiException(String code, HttpStatus status, String message, Map<String, String> fieldErrors, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = status;
        this.fieldErrors = fieldErrors == null ? null : Map.copyOf(fieldErrors);
    }

    public static ApiException validation(Map<String, String> fieldErrors) {
        String first = fieldErrors.entrySet().iterator().next().getKey() + ": " + fieldErrors.values().iterator().next();
        return new ApiException("VALIDATION_ERROR", HttpStatus.BAD_REQUEST, first, fieldErrors, null);
    }

    public static ApiException notFound(String message) {
        return new ApiException("NOT_FOUND", HttpStatus.NOT_FOUND, message);
    }

    public static ApiException conflict(String message) {
        return new ApiException("CONFLICT", HttpStatus.CONFLICT, message);
    }

    public String code() {
        return code;
    }

    public HttpStatus status() {
        return status;
    }

    public Map<String, String> fieldErrors() {
        return fieldErrors;
    }
}
