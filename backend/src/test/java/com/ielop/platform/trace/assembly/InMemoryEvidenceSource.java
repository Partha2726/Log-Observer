package com.ielop.platform.trace.assembly;

import com.ielop.platform.logsearch.HitList;
import com.ielop.platform.logsearch.LogCriteria.TimeRange;
import com.ielop.platform.logsearch.LogHit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

/**
 * The assembly queries over an in-memory list of hits, with the same semantics as
 * the Elasticsearch source. {@code reversed} returns every result in reverse order
 * (TRACE-011); {@code seedCap} simulates the seed-query cap (TRACE-032).
 */
public final class InMemoryEvidenceSource implements TraceEvidenceSource {

    private final List<LogHit> hits;
    private final boolean reversed;
    private final int seedCap;
    final AtomicInteger queries = new AtomicInteger();

    public InMemoryEvidenceSource(List<LogHit> hits) {
        this(hits, false, 5_000);
    }

    InMemoryEvidenceSource(List<LogHit> hits, boolean reversed, int seedCap) {
        this.hits = List.copyOf(hits);
        this.reversed = reversed;
        this.seedCap = seedCap;
    }

    private List<LogHit> where(Predicate<LogHit> p) {
        queries.incrementAndGet();
        List<LogHit> out = new ArrayList<>(hits.stream().filter(p).sorted(Assembler.ORDER).toList());
        if (reversed) {
            java.util.Collections.reverse(out);
        }
        return out;
    }

    @Override
    public HitList seed(String flowRef) {
        List<LogHit> all = where(h -> h.flowRefs().contains(flowRef));
        return all.size() > seedCap ? new HitList(all.subList(0, seedCap), true) : new HitList(all, false);
    }

    @Override
    public List<LogHit> byTraceIds(Collection<String> traceIds) {
        return where(h -> h.traceId() != null && traceIds.contains(h.traceId()));
    }

    @Override
    public List<LogHit> byRequestIds(Collection<String> requestIds) {
        return where(h -> h.requestId() != null && requestIds.contains(h.requestId()));
    }

    @Override
    public List<LogHit> sessionEvents(Collection<String> sessionIds, Collection<String> actions) {
        return where(h -> h.sessionId() != null && sessionIds.contains(h.sessionId())
                && h.eventAction() != null && actions.contains(h.eventAction()));
    }

    @Override
    public Optional<LogHit> lastEventBefore(String userId, Collection<String> actions, Instant before) {
        return where(h -> userId.equals(h.userId()) && h.eventAction() != null && actions.contains(h.eventAction())
                && h.timestamp().isBefore(before)).stream().max(Assembler.ORDER);
    }

    @Override
    public List<LogHit> onThread(String host, String thread, List<TimeRange> ranges) {
        return where(h -> host.equals(h.host()) && thread.equals(h.thread()) && in(h, ranges));
    }

    @Override
    public List<LogHit> onHost(String host, List<TimeRange> ranges) {
        return where(h -> host.equals(h.host()) && in(h, ranges));
    }

    @Override
    public Map<String, Set<String>> flowRefsByRequestId(Collection<String> requestIds) {
        Map<String, Set<String>> out = new TreeMap<>();
        for (LogHit h : where(h -> h.requestId() != null && requestIds.contains(h.requestId()) && !h.flowRefs().isEmpty())) {
            out.computeIfAbsent(h.requestId(), k -> new TreeSet<>()).addAll(h.flowRefs());
        }
        return out;
    }

    private static boolean in(LogHit h, List<TimeRange> ranges) {
        return ranges.stream().anyMatch(r -> !h.timestamp().isBefore(r.from()) && !h.timestamp().isAfter(r.to()));
    }

    static Comparator<LogHit> order() {
        return Objects.requireNonNull(Assembler.ORDER);
    }
}
