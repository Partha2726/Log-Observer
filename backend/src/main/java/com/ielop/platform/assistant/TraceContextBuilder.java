package com.ielop.platform.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ielop.platform.flowdef.FlowDefinition;
import com.ielop.platform.logsearch.LogHit;
import com.ielop.platform.logsearch.LogQueryService;
import com.ielop.platform.trace.StoredTrace;
import com.ielop.platform.trace.TraceAnomalies;
import com.ielop.platform.trace.TraceService;
import com.ielop.platform.trace.entity.TraceActorEntity;
import com.ielop.platform.trace.entity.TraceEntity;
import com.ielop.platform.trace.entity.TraceEventLinkEntity;
import com.ielop.platform.trace.entity.TraceStageEntity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Builds the redacted {@link TraceContext} of a trace (section 11.3) and its {@code context_hash}.
 * Evidence selection, at most 40 lines and {@code max-context-tokens}, by priority: every ERROR and
 * WARN line; the first and last line of each stage; every line an anomaly cites; stack-trace lines;
 * then OBSERVED before CORRELATED, each by time. The chosen lines are presented in time order as
 * {@code ev1…evN}; the server keeps the map to Elasticsearch document IDs. The same stored trace
 * always gives the same context and hash.
 */
@Component
public class TraceContextBuilder {

    public static final int MAX_EVIDENCE = 40;
    static final int MAX_TEXT = 600;
    static final int MAX_STACK_LINES = 15;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** A built context: what the model sees, the server-side evidence map and the hash. */
    public record Built(TraceContext context, Map<String, String> evidenceDocIds, String hash, long traceId) {
    }

    private final TraceService traces;
    private final LogQueryService logs;
    private final ObjectProvider<TraceAnomalies> anomalies;
    private final Redactor redactor;
    private final AiProperties props;
    private final ObjectMapper canonical;

    public TraceContextBuilder(TraceService traces, LogQueryService logs, ObjectProvider<TraceAnomalies> anomalies, Redactor redactor,
                               AiProperties props, ObjectMapper json) {
        this.traces = traces;
        this.logs = logs;
        this.anomalies = anomalies;
        this.redactor = redactor;
        this.props = props;
        this.canonical = json.copy().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
                .configure(SerializationFeature.INDENT_OUTPUT, false);
    }

    public Built build(String flowRef) {
        StoredTrace stored = traces.getOrAssemble(flowRef, false);
        return build(stored, traces.definitionFor(flowRef));
    }

    public Built build(StoredTrace stored, FlowDefinition def) {
        TraceEntity t = stored.trace();
        TraceAnomalies source = anomalies.getIfAvailable();
        List<TraceAnomalies.View> found = source == null ? List.of()
                : source.forTrace(t.getId()).stream().filter(a -> !"DISMISSED".equals(a.status())).toList();

        List<TraceEventLinkEntity> links = stored.links().stream().sorted(Comparator.comparingInt(TraceEventLinkEntity::getSeq)).toList();
        List<TraceEventLinkEntity> ranked = rank(links, found);
        Map<String, LogHit> hits = logs.getByIds("ai.context", ranked.stream().limit(MAX_EVIDENCE).map(TraceEventLinkEntity::getEsDocId).toList())
                .stream().collect(Collectors.toMap(LogHit::id, Function.identity(), (a, b) -> a));

        TraceContext.Trace trace = trace(stored, def);
        List<TraceContext.Anomaly> anomalyItems = found.stream()
                .map(a -> new TraceContext.Anomaly(a.ruleId(), a.ruleName(), a.severity(), redactMap(a.details()))).toList();

        // Fill by priority within the line and token limits, then present in time order.
        int budget = props.getMaxContextTokens() - tokens(trace) - tokens(anomalyItems) - 500;
        List<TraceEventLinkEntity> chosen = new ArrayList<>();
        Map<String, String> texts = new LinkedHashMap<>();
        int cut = 0;
        for (TraceEventLinkEntity l : ranked) {
            if (chosen.size() == MAX_EVIDENCE) {
                break;
            }
            String[] text = text(hits.get(l.getEsDocId()), l.isStacktrace());
            int cost = (text[0].length() + 160) / 4;
            if (cost > budget) {
                break;
            }
            budget -= cost;
            chosen.add(l);
            texts.put(l.getEsDocId(), text[0]);
            cut += text[1] != null ? 1 : 0;
        }
        chosen.sort(Comparator.comparing(TraceEventLinkEntity::getEventTs).thenComparingInt(TraceEventLinkEntity::getSeq));
        List<TraceContext.Evidence> evidence = new ArrayList<>();
        Map<String, String> evMap = new LinkedHashMap<>();
        for (TraceEventLinkEntity l : chosen) {
            String id = "ev" + (evidence.size() + 1);
            LogHit h = hits.get(l.getEsDocId());
            evMap.put(id, l.getEsDocId());
            evidence.add(new TraceContext.Evidence(id, l.getStage(), l.getService(), TS.format(l.getEventTs()), level(l, h),
                    l.getEvidenceClass(), l.getLogger(), l.getHttpStatus(), texts.get(l.getEsDocId())));
        }
        TraceContext context = new TraceContext(TraceContext.VERSION, trace, anomalyItems, evidence,
                new TraceContext.Truncation(links.size() - chosen.size(), cut));
        return new Built(context, evMap, hash(context), t.getId());
    }

    /** Every link in priority order, without duplicates. */
    static List<TraceEventLinkEntity> rank(List<TraceEventLinkEntity> links, List<TraceAnomalies.View> anomalies) {
        Set<TraceEventLinkEntity> out = new LinkedHashSet<>();
        links.stream().filter(l -> l.isError() || l.isWarning()).forEach(out::add);
        Map<String, List<TraceEventLinkEntity>> byStage = links.stream()
                .collect(Collectors.groupingBy(TraceEventLinkEntity::getStage, LinkedHashMap::new, Collectors.toList()));
        byStage.values().forEach(stage -> {
            out.add(stage.get(0));
            out.add(stage.get(stage.size() - 1));
        });
        Set<String> cited = anomalies.stream().flatMap(a -> a.evidenceDocIds().stream()).collect(Collectors.toSet());
        links.stream().filter(l -> cited.contains(l.getEsDocId())).forEach(out::add);
        links.stream().filter(TraceEventLinkEntity::isStacktrace).forEach(out::add);
        links.stream().filter(l -> "OBSERVED".equals(l.getEvidenceClass())).forEach(out::add);
        out.addAll(links);
        return List.copyOf(out);
    }

    /** [text, non-null when something was cut]: redacted first, then at most 15 stack-trace lines and 600 characters. */
    String[] text(LogHit h, boolean stacktrace) {
        if (h == null) {
            return new String[]{"[log line no longer in Elasticsearch]", null};
        }
        String raw = h.messageBody() != null ? h.messageBody() : h.message() != null ? h.message() : "";
        String s = redactor.redact(raw);
        boolean cut = false;
        if (stacktrace) {
            String[] lines = s.split("\n", -1);
            if (lines.length > MAX_STACK_LINES) {
                s = String.join("\n", java.util.Arrays.copyOf(lines, MAX_STACK_LINES));
                cut = true;
            }
        }
        if (s.length() > MAX_TEXT) {
            s = s.substring(0, MAX_TEXT - 1) + "…";
            cut = true;
        }
        return new String[]{s, cut ? "cut" : null};
    }

    private static String level(TraceEventLinkEntity l, LogHit h) {
        if (h != null && h.level() != null) {
            return h.level();
        }
        return l.isError() ? "ERROR" : l.isWarning() ? "WARN" : "INFO";
    }

    private TraceContext.Trace trace(StoredTrace s, FlowDefinition def) {
        TraceEntity t = s.trace();
        Map<String, String> byRole = new LinkedHashMap<>();
        s.actors().stream().sorted(Comparator.comparing(TraceActorEntity::getRole)).forEach(a -> byRole.put(a.getRole(), a.getUserId()));
        Map<String, String> roles = new LinkedHashMap<>();
        def.roles().stream().filter(byRole::containsKey).forEach(r -> roles.put(r, byRole.get(r)));
        byRole.forEach(roles::putIfAbsent);
        List<TraceContext.Stage> stages = s.stages().stream().sorted(Comparator.comparingInt(TraceStageEntity::getStageOrder))
                .map(st -> new TraceContext.Stage(st.getStage(), st.getStatus(), ts(st.getStartedAt()), ts(st.getEndedAt()),
                        st.getStartedAt() == null || st.getEndedAt() == null ? null : Duration.between(st.getStartedAt(), st.getEndedAt()).toMillis(),
                        st.getUserId()))
                .toList();
        TraceContext.FirstFailure ff = null;
        if (t.getFirstFailureStage() != null) {
            String service = s.links().stream().filter(l -> l.getEsDocId().equals(t.getFirstFailureDocId()))
                    .map(TraceEventLinkEntity::getService).findFirst().orElse(null);
            ff = new TraceContext.FirstFailure(t.getFirstFailureStage(), service, redactor.redact(t.getFirstFailureComponent()),
                    t.getFirstFailureException());
        }
        return new TraceContext.Trace(t.getFlowId(), t.getFlowRef(), t.getOverallStatus(), roles, t.getRequesterUserId(),
                t.getApproverUserId(), t.getExecutorUserId(), stages, ff);
    }

    private Map<String, Object> redactMap(Map<String, Object> details) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (details != null) {
            details.forEach((k, v) -> out.put(k, redactValue(v)));
        }
        return out;
    }

    private Object redactValue(Object v) {
        if (v instanceof String s) {
            return redactor.redact(s);
        }
        if (v instanceof List<?> list) {
            return list.stream().map(this::redactValue).toList();
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), redactValue(x)));
            return out;
        }
        return v;
    }

    private int tokens(Object o) {
        try {
            return canonical.writeValueAsString(o).length() / 4;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** SHA-256 of the canonical JSON (maps sorted by key, records in declared order). */
    public String hash(TraceContext context) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.writeValueAsString(context).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String json(TraceContext context) {
        try {
            return canonical.writeValueAsString(context);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String ts(Instant i) {
        return i == null ? null : TS.format(i);
    }
}
