package com.ielop.platform.detection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Anomaly API bodies (section 8.8). */
public final class AnomalyDtos {

    private AnomalyDtos() {
    }

    /** One row of {@code GET /api/anomalies}. */
    public record Item(long id, String ruleId, String ruleName, String severity, String status, String flowRef, String userId,
                       String clientIp, Instant detectedAt, Instant windowStart, Instant windowEnd, Map<String, Object> details,
                       int evidenceCount, int version) {
    }

    /** One past review, from the audit trail (empty until audit events are persisted). */
    public record Review(String actor, String fromStatus, String toStatus, String note, Instant at) {
    }

    /** {@code GET /api/anomalies/{id}} and the {@code PATCH} response. */
    public record Detail(long id, String ruleId, String ruleName, String severity, String status, String flowRef, String userId,
                         String clientIp, Instant detectedAt, Instant windowStart, Instant windowEnd, Map<String, Object> details,
                         List<String> evidenceDocIds, int version, String reviewedBy, String reviewNote, Instant reviewedAt,
                         List<Review> history) {
    }

    /** {@code PATCH /api/anomalies/{id}} body. */
    public record ReviewRequest(String status, String note, Integer version) {
    }
}
