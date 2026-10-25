package com.ielop.generator.model;

/**
 * A non-flow scenario for {@code incidents.jsonl} (spec section 5.6): who or which
 * client IP, and the time window in which the expected rule should fire.
 */
public record Incident(Scenario scenario, String userId, String clientIp, long startMillis, long endMillis,
                       String relatedFlowRef, int events, String expectedRule) {
}
