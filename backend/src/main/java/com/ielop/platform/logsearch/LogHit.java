package com.ielop.platform.logsearch;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One parsed log event as stored in Elasticsearch, identified by its document ID.
 * Absent fields are null (lists are empty).
 */
public record LogHit(
        String id,
        String index,
        Instant timestamp,
        String service,
        String host,
        String logFormat,
        String level,
        String logger,
        String thread,
        String method,
        Integer line,
        List<String> flowRefs,
        String userId,
        String sessionId,
        String requestId,
        String eventAction,
        String clientIp,
        String httpMethod,
        String urlPath,
        Integer httpStatus,
        Double durationSeconds,
        String userAgent,
        String errorType,
        String providerStatus,
        boolean isError,
        boolean isWarning,
        boolean hasStacktrace,
        String filePath,
        long offset,
        Instant ingestedAt,
        BigDecimal amount,
        BigDecimal limitAmount,
        String currency,
        String counterpartyHash,
        String counterpartyLast4,
        String reason,
        String permission,
        String resource,
        String mfa,
        List<String> externalCorrelationIds,
        String messageBody,
        String message,
        List<String> tags,
        /** OpenTelemetry trace and span IDs (phase 3; null without --otel). */
        String traceId,
        String spanId
) {
    public boolean hasFlowRef(String flowRef) {
        return flowRefs.contains(flowRef);
    }

    public boolean parseFailed() {
        return tags.stream().anyMatch(t -> t.endsWith("parsefailure"));
    }
}
