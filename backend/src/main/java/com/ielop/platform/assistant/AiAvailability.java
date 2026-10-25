package com.ielop.platform.assistant;

/**
 * Whether AI features run, decided once at start-up from {@link AiProperties}. A disabled AI is
 * a normal state (no key, kill switch), reported as {@code DISABLED} and never as {@code DOWN}.
 */
public record AiAvailability(boolean enabled, String provider, String model, String reason) {

    public static AiAvailability disabled(String provider, String model, String reason) {
        return new AiAvailability(false, provider, model, reason);
    }
}
