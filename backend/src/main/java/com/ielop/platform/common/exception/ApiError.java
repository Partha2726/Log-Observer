package com.ielop.platform.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ielop.platform.common.filter.RequestIdFilter;

import java.time.Instant;
import java.util.Map;

/**
 * Standard, consistent shape for error responses returned by the API.
 *
 * <p>Conforms to the spec's error structure (section 8.4) ({@code code, message, timestamp, requestId})
 * while preserving compatible HTTP context fields ({@code status, error, path, fieldErrors}).</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String code,
        String message,
        Instant timestamp,
        String requestId,
        int status,
        String error,
        String path,
        Map<String, String> fieldErrors
) {
    public static ApiError of(int status, String error, String message, String path) {
        return of("API_ERROR", status, error, message, path, null);
    }

    public static ApiError of(String code, int status, String error, String message, String path) {
        return of(code, status, error, message, path, null);
    }

    public static ApiError of(
            String code,
            int status,
            String error,
            String message,
            String path,
            Map<String, String> fieldErrors
    ) {
        return new ApiError(
                code != null ? code : "API_ERROR",
                message,
                Instant.now(),
                RequestIdFilter.getCurrentRequestId(),
                status,
                error,
                path,
                fieldErrors
        );
    }
}
