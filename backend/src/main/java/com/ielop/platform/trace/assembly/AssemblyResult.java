package com.ielop.platform.trace.assembly;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Everything one assembly concluded about a flow, before persistence. Equal input
 * gives an equal result (TRACE-010): lists are in trace order, maps in definition
 * (roles) order. {@code roles} are all the definition's roles in order; {@code actors}
 * holds those that were found.
 */
public record AssemblyResult(
        String flowRef,
        String flowId,
        int flowVersion,
        List<String> roles,
        List<TraceLink> links,
        List<StageResult> stages,
        Map<String, String> actors,
        String overallStatus,
        FirstFailure firstFailure,
        Instant firstSeen,
        Instant lastSeen,
        BigDecimal amount,
        String currency,
        String counterpartyHash,
        String counterpartyLast4,
        BigDecimal observedRatio,
        int excludedCount,
        boolean truncated,
        String configHash
) {
}
