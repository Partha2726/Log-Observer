package com.ielop.platform.trace.assembly;

import com.ielop.platform.logsearch.HitList;
import com.ielop.platform.logsearch.LogCriteria.TimeRange;
import com.ielop.platform.logsearch.LogHit;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The queries trace assembly needs (section 7.1). Production reads Elasticsearch
 * through {@code logsearch}; tests use an in-memory list of hits. Implementations
 * may return hits in any order: the assembler sorts everything itself.
 */
public interface TraceEvidenceSource {

    /** Step 1: lines whose {@code flow.refs} contain the flowRef, up to the seed cap. */
    HitList seed(String flowRef);

    /** Step 2: lines carrying any of these request IDs. */
    List<LogHit> byRequestIds(Collection<String> requestIds);

    /** Step 2b (phase 3): lines carrying any of these OpenTelemetry trace IDs. */
    default List<LogHit> byTraceIds(Collection<String> traceIds) {
        return List.of();
    }

    /** Step 3: lines of these sessions whose event action is one of {@code actions}. */
    List<LogHit> sessionEvents(Collection<String> sessionIds, Collection<String> actions);

    /** Step 3 fallback: the user's latest line with one of {@code actions} strictly before {@code before}. */
    Optional<LogHit> lastEventBefore(String userId, Collection<String> actions, Instant before);

    /** Step 6: lines on one host and thread inside any of the ranges. */
    List<LogHit> onThread(String host, String thread, List<TimeRange> ranges);

    /** Step 7: every line on one host inside any of the ranges. */
    List<LogHit> onHost(String host, List<TimeRange> ranges);

    /** For each request ID, the flowRefs carried by lines with that request ID (absent when none). */
    Map<String, Set<String>> flowRefsByRequestId(Collection<String> requestIds);
}
