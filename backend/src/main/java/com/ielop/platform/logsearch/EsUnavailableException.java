package com.ielop.platform.logsearch;

import com.ielop.platform.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Elasticsearch could not answer: connection refused, timeout or a server error.
 * Maps to 503 {@code ES_UNAVAILABLE} (spec section 8.4, F-01, F-02).
 */
public class EsUnavailableException extends ApiException {

    public static final String CODE = "ES_UNAVAILABLE";

    public EsUnavailableException(String message, Throwable cause) {
        super(CODE, HttpStatus.SERVICE_UNAVAILABLE, message, null, cause);
    }
}
