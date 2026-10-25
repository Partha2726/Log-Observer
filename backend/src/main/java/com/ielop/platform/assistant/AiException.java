package com.ielop.platform.assistant;

import com.ielop.platform.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** AI error codes (section 8.4). */
public final class AiException {

    public static final String AI_DISABLED = "AI_DISABLED";
    public static final String AI_PROVIDER_ERROR = "AI_PROVIDER_ERROR";
    public static final String AI_OUTPUT_INVALID = "AI_OUTPUT_INVALID";
    public static final String AI_BUDGET_EXCEEDED = "AI_BUDGET_EXCEEDED";

    private AiException() {
    }

    public static ApiException disabled(String reason) {
        return new ApiException(AI_DISABLED, HttpStatus.SERVICE_UNAVAILABLE, "AI features are disabled: " + reason);
    }

    public static ApiException providerError(String message, Throwable cause) {
        return new ApiException(AI_PROVIDER_ERROR, HttpStatus.BAD_GATEWAY, message, null, cause);
    }

    /** Every provider of the chain failed: a short message for users; the per-provider statuses are only logged. */
    public static ApiException providerBusy(java.util.List<String> failures) {
        return new ApiException(AI_PROVIDER_ERROR, HttpStatus.BAD_GATEWAY, "AI provider busy; try again in a minute.");
    }

    public static ApiException outputInvalid(String message) {
        return new ApiException(AI_OUTPUT_INVALID, HttpStatus.BAD_GATEWAY, message);
    }

    public static ApiException budgetExceeded(long used, long budget) {
        return new ApiException(AI_BUDGET_EXCEEDED, HttpStatus.TOO_MANY_REQUESTS,
                "Daily AI token budget used up (" + used + " of " + budget + " tokens); try again tomorrow (UTC)");
    }
}
