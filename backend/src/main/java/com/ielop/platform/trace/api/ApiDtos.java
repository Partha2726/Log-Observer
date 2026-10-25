package com.ielop.platform.trace.api;

import com.ielop.platform.trace.TraceAnomalies;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Response bodies of the search, user activity, request and trace endpoints (spec section 8.8). */
public final class ApiDtos {

    private ApiDtos() {
    }

    /** One row of {@code GET /api/search/flows}. Unassembled flowRefs found in Elasticsearch have {@code assembled: false}. */
    public record FlowSummary(
            String flowRef,
            String flowId,
            String overallStatus,
            Map<String, String> actors,
            String requester,
            String approver,
            String executor,
            Instant firstSeen,
            Instant lastSeen,
            BigDecimal amount,
            String currency,
            int anomalyCount,
            String maxSeverity,
            boolean assembled
    ) {
    }

    /** One row of {@code GET /api/search/logs} and of a request's own lines. */
    public record LogLine(
            String docId,
            Instant ts,
            String service,
            String host,
            String stage,
            String sourceFile,
            String level,
            String logger,
            String thread,
            String requestId,
            List<String> flowRefs,
            String userId,
            String eventAction,
            Integer httpStatus,
            String snippet,
            String highlight
    ) {
    }

    public record Session(String sessionId, Instant loginAt, Instant logoutAt, String endReason, String clientIp,
                          String mfa, int failedAttemptsBefore) {
    }

    public record FailedSignIn(Instant ts, String clientIp, String reason, String eventAction) {
    }

    public record UserFlow(String flowRef, String flowId, String role, String overallStatus, Instant ts) {
    }

    public record PermissionDenial(Instant ts, String permission, String resource) {
    }

    /** {@code GET /api/users/{userId}/activity} (section 7.3), with the anomalies naming the user in the range. */
    public record UserActivity(String userId, Instant from, Instant to, List<Session> sessions, List<FailedSignIn> failedSignIns,
                               List<UserFlow> flows, List<PermissionDenial> permissionDenials,
                               List<TraceAnomalies.View> anomalies) {
    }

    /** {@code GET /api/requests/{requestId}}: the request's flow, or its own lines when it belongs to no flow (F-10a). */
    public record RequestLookup(String requestId, String flowRef, List<String> flowRefs, List<LogLine> lines) {
    }

    public record Stage(String stage, int order, String status, String userId, Instant startedAt, Instant endedAt,
                        Long durationMs, int errorCount) {
    }

    public record FirstFailure(String stage, String component, String exception, Integer httpStatus, String docId) {
    }

    /** {@code GET /api/traces/{flowRef}}. */
    public record Trace(
            String flowRef,
            String flowId,
            int flowVersion,
            String overallStatus,
            Map<String, String> actors,
            String requester,
            String approver,
            String executor,
            BigDecimal amount,
            String currency,
            String counterpartyLast4,
            Instant firstSeen,
            Instant lastSeen,
            Long durationMs,
            List<Stage> stages,
            FirstFailure firstFailure,
            List<TraceAnomalies.View> anomalies,
            BigDecimal observedRatio,
            int excludedCount,
            boolean truncated,
            int linkCount,
            Instant assembledAt,
            int assemblyVersion
    ) {
    }

    /** One evidence line of {@code GET /api/traces/{flowRef}/events}; {@code expired} when Elasticsearch no longer has it (F-17). */
    public record EvidenceLine(
            String docId,
            Instant ts,
            String service,
            String host,
            String stage,
            String sourceFile,
            String level,
            String logger,
            String thread,
            String requestId,
            String evidenceClass,
            BigDecimal confidence,
            String linkReason,
            String message,
            Map<String, Object> parsed,
            boolean isStacktrace,
            boolean expired
    ) {
    }
}
