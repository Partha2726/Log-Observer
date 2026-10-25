package com.ielop.platform.detection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One rule finding before it is stored. {@code dedupKey} (rule + subject + window,
 * section 10.1) makes repeated evaluation idempotent; {@code evidenceDocIds} are the
 * Elasticsearch documents that prove it (never empty, RULE-022).
 */
public record Finding(
        String ruleId,
        Severity severity,
        String dedupKey,
        Long traceId,
        String flowRef,
        String userId,
        String clientIp,
        Instant windowStart,
        Instant windowEnd,
        Map<String, Object> details,
        List<String> evidenceDocIds
) {
    public Finding {
        if (evidenceDocIds == null || evidenceDocIds.isEmpty()) {
            throw new IllegalArgumentException(ruleId + " finding " + dedupKey + " cites no evidence");
        }
        evidenceDocIds = List.copyOf(evidenceDocIds);
        details = Map.copyOf(details);
    }
}
