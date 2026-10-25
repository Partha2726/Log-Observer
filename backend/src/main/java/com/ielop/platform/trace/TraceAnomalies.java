package com.ielop.platform.trace;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Anomalies of a trace, for the trace API and graph badges. Implemented by the
 * detection package, which depends on trace (never the other way, ARCH-002).
 */
public interface TraceAnomalies {

    /** One anomaly as the trace view shows it. */
    record View(long id, String ruleId, String ruleName, String severity, String status, Map<String, Object> details,
                List<String> evidenceDocIds, int version) {
    }

    List<View> forTrace(long traceId);

    /** Anomalies naming {@code userId} whose window overlaps {@code [from, to]}, newest first (at most 100). */
    List<View> forUser(String userId, Instant from, Instant to);
}
