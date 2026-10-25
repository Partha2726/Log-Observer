package com.ielop.platform.common.audit;

import java.time.Instant;
import java.util.Map;

/**
 * Immutable event payload published across module boundaries for audit logging.
 *
 * <p>Centrally defined in {@code common.audit} as a contract.
 * Persistence is owned by the {@code audit} package (spec section 8.6).</p>
 */
public record AuditEventMessage(
        String action,
        String principal,
        String targetType,
        String targetId,
        Map<String, Object> details,
        Instant timestamp,
        String requestId,
        String ipAddress
) {
    public static AuditEventMessage of(String action, String principal, String targetType, String targetId) {
        return new AuditEventMessage(
                action,
                principal,
                targetType,
                targetId,
                Map.of(),
                Instant.now(),
                null,
                null
        );
    }
}
