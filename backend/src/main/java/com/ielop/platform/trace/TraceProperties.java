package com.ielop.platform.trace;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.trace.*}: the assembly parameters of spec section 7.1. Every value is
 * part of the trace configuration hash (section 7.2, TRACE-015).
 */
@ConfigurationProperties(prefix = "app.trace")
public record TraceProperties(
        long windowPadMs,
        long threadWindowMs,
        long timeOnlyWindowMs,
        double minConfidence,
        String noiseThreads
) {
    public TraceProperties {
        windowPadMs = windowPadMs <= 0 ? 2000 : windowPadMs;
        threadWindowMs = threadWindowMs <= 0 ? 500 : threadWindowMs;
        timeOnlyWindowMs = timeOnlyWindowMs <= 0 ? 300 : timeOnlyWindowMs;
        minConfidence = minConfidence <= 0 ? 0.3 : minConfidence;
        noiseThreads = noiseThreads == null || noiseThreads.isBlank() ? "^(scheduling|quartz|cache-refresh)-.*" : noiseThreads;
    }

    public static TraceProperties defaults() {
        return new TraceProperties(0, 0, 0, 0, null);
    }
}
