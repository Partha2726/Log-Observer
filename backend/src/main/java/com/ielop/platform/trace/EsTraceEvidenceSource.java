package com.ielop.platform.trace;

import com.ielop.platform.logsearch.HitList;
import com.ielop.platform.logsearch.LogCriteria;
import com.ielop.platform.logsearch.LogCriteria.TimeRange;
import com.ielop.platform.logsearch.LogHit;
import com.ielop.platform.logsearch.LogQueryService;
import com.ielop.platform.trace.assembly.TraceEvidenceSource;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Assembly queries over Elasticsearch, through {@code logsearch} only (ARCH-001). */
@Component
public class EsTraceEvidenceSource implements TraceEvidenceSource {

    /** Terms per query; well below Elasticsearch's limit and keeps requests small. */
    private static final int TERMS_CHUNK = 1_000;
    private static final int EXPANSION_CAP = 5_000;

    private final LogQueryService logs;

    public EsTraceEvidenceSource(LogQueryService logs) {
        this.logs = logs;
    }

    @Override
    public HitList seed(String flowRef) {
        return logs.findAll("trace.seed", LogCriteria.builder().flowRef(flowRef).build(), logs.properties().seedCap());
    }

    @Override
    public List<LogHit> byTraceIds(Collection<String> traceIds) {
        List<LogHit> out = new ArrayList<>();
        for (List<String> chunk : chunks(traceIds)) {
            out.addAll(logs.findAll("trace.otel", LogCriteria.builder().traceIds(chunk).build(), EXPANSION_CAP).hits());
        }
        return out;
    }

    @Override
    public List<LogHit> byRequestIds(Collection<String> requestIds) {
        List<LogHit> out = new ArrayList<>();
        for (List<String> chunk : chunks(requestIds)) {
            out.addAll(logs.findAll("trace.request", LogCriteria.builder().requestIds(chunk).build(), EXPANSION_CAP).hits());
        }
        return out;
    }

    @Override
    public List<LogHit> sessionEvents(Collection<String> sessionIds, Collection<String> actions) {
        List<LogHit> out = new ArrayList<>();
        for (List<String> chunk : chunks(sessionIds)) {
            out.addAll(logs.findAll("trace.session", LogCriteria.builder().sessionIds(chunk).eventActions(actions).build(),
                    EXPANSION_CAP).hits());
        }
        return out;
    }

    @Override
    public Optional<LogHit> lastEventBefore(String userId, Collection<String> actions, Instant before) {
        return logs.findLatest("trace.session_fallback", LogCriteria.builder().userId(userId).eventActions(actions)
                .to(before.minusMillis(1)).build());
    }

    @Override
    public List<LogHit> onThread(String host, String thread, List<TimeRange> ranges) {
        return logs.findAll("trace.thread", LogCriteria.builder().host(host).thread(thread).anyOf(ranges).build(),
                EXPANSION_CAP).hits();
    }

    @Override
    public List<LogHit> onHost(String host, List<TimeRange> ranges) {
        return logs.findAll("trace.time", LogCriteria.builder().host(host).anyOf(ranges).build(), EXPANSION_CAP).hits();
    }

    @Override
    public Map<String, Set<String>> flowRefsByRequestId(Collection<String> requestIds) {
        Map<String, Set<String>> out = new TreeMap<>();
        for (List<String> chunk : chunks(requestIds)) {
            for (LogHit h : logs.findAll("trace.request_owner", LogCriteria.builder().requestIds(chunk).hasFlowRef(true).build(),
                    EXPANSION_CAP).hits()) {
                out.computeIfAbsent(h.requestId(), k -> new TreeSet<>()).addAll(h.flowRefs());
            }
        }
        return out;
    }

    private static List<List<String>> chunks(Collection<String> values) {
        List<String> all = new ArrayList<>(new TreeSet<>(values));
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += TERMS_CHUNK) {
            out.add(all.subList(i, Math.min(all.size(), i + TERMS_CHUNK)));
        }
        return out;
    }
}
