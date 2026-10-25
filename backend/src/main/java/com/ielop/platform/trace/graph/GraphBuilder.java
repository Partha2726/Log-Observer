package com.ielop.platform.trace.graph;

import com.ielop.platform.flowdef.FlowDefinition;
import com.ielop.platform.flowdef.StageDefinition;
import com.ielop.platform.trace.StoredTrace;
import com.ielop.platform.trace.entity.TraceEntity;
import com.ielop.platform.trace.entity.TraceEventLinkEntity;
import com.ielop.platform.trace.entity.TraceStageEntity;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Builds {@code graph_version 1} (section 7.6) from stored trace rows only: it never
 * queries Elasticsearch. Industry-neutral: node kinds come from the flow definition
 * (session stages and sign-out make ACTOR nodes) and from the parsed fields (lines
 * without a logger make one SERVICE node per request; lines with a logger make one
 * COMPONENT node per logger; external correlation IDs make EXTERNAL nodes).
 */
public final class GraphBuilder {

    public static final int DEFAULT_MAX_NODES_PER_STAGE = 12;
    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final int maxComponentsPerStage;

    public GraphBuilder(int maxComponentsPerStage) {
        this.maxComponentsPerStage = maxComponentsPerStage;
    }

    /** A node under construction: its lines in trace order. */
    private static final class Draft {
        final String kind;
        final String stage;
        final String service;
        final String logger;
        final String key;
        final List<TraceEventLinkEntity> lines = new ArrayList<>();
        String label;
        String id;

        Draft(String kind, String stage, String service, String logger, String key) {
            this.kind = kind;
            this.stage = stage;
            this.service = service;
            this.logger = logger;
            this.key = key;
        }

        Instant first() {
            return lines.get(0).getEventTs();
        }

        Instant last() {
            return lines.get(lines.size() - 1).getEventTs();
        }

        List<String> evidence() {
            return lines.stream().map(TraceEventLinkEntity::getEsDocId).toList();
        }
    }

    public TraceGraph build(StoredTrace stored, FlowDefinition def) {
        return build(stored, def, Map.of());
    }

    /**
     * As {@link #build(StoredTrace, FlowDefinition)}, with {@code anomalyIdsByDoc} mapping an
     * evidence document ID to the anomalies that cite it: a node's {@code anomalyIds} are the
     * anomalies citing any of its lines.
     */
    public TraceGraph build(StoredTrace stored, FlowDefinition def, Map<String, List<Long>> anomalyIdsByDoc) {
        TraceEntity trace = stored.trace();
        Map<String, TraceStageEntity> stageRows = new LinkedHashMap<>();
        stored.stages().forEach(s -> stageRows.put(s.getStage(), s));
        List<TraceEventLinkEntity> links = stored.links().stream().sorted(Comparator.comparingInt(TraceEventLinkEntity::getSeq)).toList();
        Set<String> sessionStages = new LinkedHashSet<>();
        def.stages().stream().filter(StageDefinition::isSessionStage).forEach(s -> sessionStages.add(s.name()));
        String firstStage = def.stages().get(0).name();

        // Lane of every sign-out line: the latest stage in which that user or session acted before it.
        Map<String, String> laneOf = new HashMap<>();
        for (TraceEventLinkEntity l : links) {
            laneOf.put(l.getEsDocId(), FlowDefinition.SIGN_OUT.equals(l.getStage()) ? signOutLane(l, links, firstStage) : l.getStage());
        }

        // 1. Group lines into node drafts.
        Map<String, Draft> drafts = new LinkedHashMap<>();
        Map<String, String> sessionByRequest = new HashMap<>();
        for (TraceEventLinkEntity l : links) {
            if (l.getSessionId() != null && l.getRequestId() != null) {
                sessionByRequest.putIfAbsent(l.getRequestId(), l.getSessionId());
            }
        }
        for (TraceEventLinkEntity l : links) {
            String lane = laneOf.get(l.getEsDocId());
            boolean signOut = FlowDefinition.SIGN_OUT.equals(l.getStage());
            Draft d;
            if (signOut || sessionStages.contains(l.getStage())) {
                String who = Optional.ofNullable(l.getSessionId())
                        .or(() -> Optional.ofNullable(l.getRequestId()).map(sessionByRequest::get))
                        .or(() -> Optional.ofNullable(l.getUserId()).map(u -> "user:" + u))
                        .orElse("request:" + Objects.toString(l.getRequestId(), l.getEsDocId()));
                String key = "A|" + lane + "|" + (signOut ? "out" : "in") + "|" + who;
                d = drafts.computeIfAbsent(key, k -> new Draft("ACTOR", lane, l.getService(), null, k));
            } else if (l.getLogger() == null) {
                String key = "S|" + lane + "|" + l.getService() + "|" + Objects.toString(l.getRequestId(), "-");
                d = drafts.computeIfAbsent(key, k -> new Draft("SERVICE", lane, l.getService(), null, k));
            } else {
                String key = "C|" + lane + "|" + l.getService() + "|" + l.getLogger();
                d = drafts.computeIfAbsent(key, k -> new Draft("COMPONENT", lane, l.getService(), l.getLogger(), k));
            }
            d.lines.add(l);
        }
        // EXTERNAL nodes: one per external correlation ID per lane, from the lines that carry it.
        Map<String, Draft> externals = new LinkedHashMap<>();
        for (TraceEventLinkEntity l : links) {
            if (l.getExternalIds() == null) {
                continue;
            }
            for (String ext : l.getExternalIds().split(",")) {
                String key = "E|" + laneOf.get(l.getEsDocId()) + "|" + ext;
                externals.computeIfAbsent(key, k -> new Draft("EXTERNAL", laneOf.get(l.getEsDocId()), null, null, k)).lines.add(l);
            }
        }

        // 2. Cap COMPONENT nodes per lane; merge the rest into one node that keeps all their evidence.
        List<Draft> nodes = new ArrayList<>();
        for (StageDefinition s : def.stages()) {
            List<Draft> lane = drafts.values().stream().filter(d -> d.stage.equals(s.name())).sorted(DRAFT_ORDER).toList();
            List<Draft> components = lane.stream().filter(d -> d.kind.equals("COMPONENT")).toList();
            List<Draft> kept = new ArrayList<>(lane.stream().filter(d -> !d.kind.equals("COMPONENT")).toList());
            if (components.size() > maxComponentsPerStage) {
                kept.addAll(components.subList(0, maxComponentsPerStage - 1));
                List<Draft> rest = components.subList(maxComponentsPerStage - 1, components.size());
                Draft merged = new Draft("COMPONENT", s.name(), null, null, "C|" + s.name() + "|more");
                rest.forEach(r -> merged.lines.addAll(r.lines));
                merged.lines.sort(Comparator.comparingInt(TraceEventLinkEntity::getSeq));
                merged.label = rest.size() + " more components";
                kept.add(merged);
            } else {
                kept.addAll(components);
            }
            externals.values().stream().filter(d -> d.stage.equals(s.name())).forEach(kept::add);
            kept.sort(DRAFT_ORDER);
            for (int i = 0; i < kept.size(); i++) {
                kept.get(i).id = String.format("n-%s-%02d", s.name(), i + 1);
            }
            nodes.addAll(kept);
        }

        // 3. Node fields.
        Map<String, String> stageStatus = new HashMap<>();
        stageRows.forEach((name, row) -> stageStatus.put(name, row.getStatus()));
        String firstFailureDoc = "FAILED".equals(trace.getOverallStatus()) ? trace.getFirstFailureDocId() : null;
        List<TraceGraph.Node> outNodes = new ArrayList<>();
        Map<Draft, String> statusOf = new HashMap<>();
        for (Draft d : nodes) {
            boolean firstFailure = firstFailureDoc != null && d.evidence().contains(firstFailureDoc) && !d.kind.equals("EXTERNAL");
            String status = nodeStatus(d, def, stageStatus.get(d.stage), firstFailure);
            statusOf.put(d, status);
            String exception = d.lines.stream().map(TraceEventLinkEntity::getErrorType).filter(Objects::nonNull).findFirst().orElse(null);
            BigDecimal minConfidence = d.lines.stream().map(TraceEventLinkEntity::getConfidence).min(Comparator.naturalOrder()).orElse(null);
            outNodes.add(new TraceGraph.Node(d.id, d.kind, d.stage, d.label != null ? d.label : label(d), d.service, d.logger,
                    status, exception, d.lines.size(), TS.format(d.first()), TS.format(d.last()), d.evidence(),
                    anomalies(d.evidence(), anomalyIdsByDoc), minConfidence, firstFailure));
        }
        for (StageDefinition s : def.stages()) {
            if ("NOT_REACHED".equals(stageStatus.get(s.name())) && nodes.stream().noneMatch(n -> n.stage.equals(s.name()))) {
                outNodes.add(new TraceGraph.Node(String.format("n-%s-00", s.name()), "PLACEHOLDER", s.name(), "Not reached",
                        null, null, "NOT_REACHED", null, 0, null, null, List.of(), List.of(), null, false));
            }
        }

        // 4. Edges.
        List<EdgeDraft> edges = new ArrayList<>();
        Map<String, Integer> laneIndex = new HashMap<>();
        def.stages().forEach(s -> laneIndex.put(s.name(), s.order()));
        for (StageDefinition s : def.stages()) {
            List<Draft> lane = nodes.stream().filter(d -> d.stage.equals(s.name()) && !d.kind.equals("EXTERNAL")).toList();
            for (int i = 1; i < lane.size(); i++) {
                edges.add(new EdgeDraft(lane.get(i - 1), lane.get(i), "sequence", null,
                        List.of(last(lane.get(i - 1)), first(lane.get(i)))));
            }
        }
        List<String> reached = def.stages().stream().map(StageDefinition::name)
                .filter(name -> nodes.stream().anyMatch(d -> d.stage.equals(name) && !d.kind.equals("EXTERNAL"))).toList();
        for (int i = 1; i < reached.size(); i++) {
            String from = reached.get(i - 1);
            String to = reached.get(i);
            Draft target = nodes.stream().filter(d -> d.stage.equals(to) && !d.kind.equals("EXTERNAL")).min(DRAFT_ORDER).orElseThrow();
            // The last node of the stage that started no later than the target; a session stage holds every
            // actor's sign-in, so its very last node can be hours after the next stage began.
            List<Draft> fromNodes = nodes.stream().filter(d -> d.stage.equals(from) && !d.kind.equals("EXTERNAL")).sorted(DRAFT_ORDER).toList();
            Draft source = fromNodes.stream().filter(d -> !d.first().isAfter(target.first())).reduce((a, b) -> b)
                    .orElse(fromNodes.get(fromNodes.size() - 1));
            edges.add(new EdgeDraft(source, target, "handoff", handoffLabel(source, target), List.of(last(source), first(target))));
        }
        // call: the node holding a request's HTTP response (the entry point) to every other node handling that request.
        Map<String, Draft> entryByRequest = new TreeMap<>();
        for (Draft d : nodes) {
            for (TraceEventLinkEntity l : d.lines) {
                if (l.getRequestId() != null && l.getHttpStatus() != null && !d.kind.equals("EXTERNAL")) {
                    entryByRequest.putIfAbsent(l.getRequestId(), d);
                }
            }
        }
        for (var e : entryByRequest.entrySet()) {
            String rid = e.getKey();
            Draft entry = e.getValue();
            for (Draft d : nodes) {
                if (d == entry || d.kind.equals("EXTERNAL")) {
                    continue;
                }
                Optional<TraceEventLinkEntity> handled = d.lines.stream().filter(l -> rid.equals(l.getRequestId())).findFirst();
                if (handled.isPresent()) {
                    String entryLine = entry.lines.stream().filter(l -> rid.equals(l.getRequestId())).findFirst().orElseThrow().getEsDocId();
                    edges.add(new EdgeDraft(entry, d, "call", "request " + abbreviate(rid), List.of(entryLine, handled.get().getEsDocId())));
                }
            }
        }
        // integration: component to EXTERNAL (calls) and back (responses, the lines with a provider status).
        for (Draft ext : nodes) {
            if (!ext.kind.equals("EXTERNAL")) {
                continue;
            }
            String extId = ext.key.substring(ext.key.lastIndexOf('|') + 1);
            for (Draft d : nodes) {
                if (d.kind.equals("EXTERNAL") || !d.stage.equals(ext.stage)) {
                    continue;
                }
                List<TraceEventLinkEntity> carrying = d.lines.stream()
                        .filter(l -> l.getExternalIds() != null && List.of(l.getExternalIds().split(",")).contains(extId)).toList();
                List<String> calls = carrying.stream().filter(l -> l.getProviderStatus() == null).map(TraceEventLinkEntity::getEsDocId).toList();
                List<TraceEventLinkEntity> responses = carrying.stream().filter(l -> l.getProviderStatus() != null).toList();
                if (!calls.isEmpty()) {
                    edges.add(new EdgeDraft(d, ext, "integration", extId, calls));
                }
                if (!responses.isEmpty()) {
                    edges.add(new EdgeDraft(ext, d, "integration", extId + ", " + responses.get(0).getProviderStatus(),
                            responses.stream().map(TraceEventLinkEntity::getEsDocId).toList()));
                }
            }
        }
        edges.sort(Comparator.comparing((EdgeDraft e) -> e.source.first()).thenComparing(e -> e.source.id)
                .thenComparingInt(e -> KIND_ORDER.indexOf(e.kind)).thenComparing(e -> e.target.id));
        List<TraceGraph.Edge> outEdges = new ArrayList<>();
        for (int i = 0; i < edges.size(); i++) {
            EdgeDraft e = edges.get(i);
            outEdges.add(new TraceGraph.Edge(String.format("e-%02d", i + 1), e.source.id, e.target.id, e.kind,
                    e.label, List.copyOf(new LinkedHashSet<>(e.evidence))));
        }

        // 5. Lanes.
        List<TraceGraph.Lane> lanes = new ArrayList<>();
        for (StageDefinition s : def.stages()) {
            TraceStageEntity row = stageRows.get(s.name());
            Instant start = row == null ? null : row.getStartedAt();
            Instant end = row == null ? null : row.getEndedAt();
            lanes.add(new TraceGraph.Lane(s.name(), row == null ? "NOT_REACHED" : row.getStatus(), row == null ? null : row.getUserId(),
                    start == null ? null : TS.format(start), end == null ? null : TS.format(end),
                    start == null || end == null ? null : Duration.between(start, end).toMillis()));
        }
        outNodes.sort(Comparator.comparingInt((TraceGraph.Node n) -> laneIndex.get(n.stage())).thenComparing(TraceGraph.Node::id));
        return new TraceGraph(TraceGraph.VERSION, trace.getFlowId(), trace.getFlowRef(), trace.getOverallStatus(),
                lanes, List.copyOf(outNodes), List.copyOf(outEdges));
    }

    private record EdgeDraft(Draft source, Draft target, String kind, String label, List<String> evidence) {
    }

    private static final List<String> KIND_ORDER = List.of("sequence", "call", "integration", "handoff");

    /** Node order (section 7.6): first timestamp, then service, then logger name. */
    private static final Comparator<Draft> DRAFT_ORDER = Comparator.comparing(Draft::first)
            .thenComparing(d -> Objects.toString(d.service, ""))
            .thenComparing(d -> Objects.toString(d.logger, ""))
            .thenComparing(d -> d.key);

    private static List<Long> anomalies(List<String> evidence, Map<String, List<Long>> byDoc) {
        if (byDoc.isEmpty()) {
            return List.of();
        }
        return evidence.stream().flatMap(id -> byDoc.getOrDefault(id, List.of()).stream()).distinct().sorted().toList();
    }

    private static String signOutLane(TraceEventLinkEntity line, List<TraceEventLinkEntity> links, String fallback) {
        String lane = null;
        for (TraceEventLinkEntity l : links) {
            if (FlowDefinition.SIGN_OUT.equals(l.getStage()) || l.getEventTs().isAfter(line.getEventTs())) {
                continue;
            }
            boolean sameActor = (line.getSessionId() != null && line.getSessionId().equals(l.getSessionId()))
                    || (line.getUserId() != null && line.getUserId().equals(l.getUserId()));
            if (sameActor) {
                lane = l.getStage();
            }
        }
        return lane != null ? lane : fallback;
    }

    private static String nodeStatus(Draft d, FlowDefinition def, String stageStatus, boolean firstFailure) {
        if (firstFailure) {
            return "FAILED";
        }
        if ("FAILED".equals(stageStatus) && d.lines.stream().anyMatch(l -> l.isError()
                && ("OBSERVED".equals(l.getEvidenceClass()) || l.getConfidence().compareTo(new BigDecimal("0.80")) >= 0))) {
            return "FAILED";
        }
        if ("REJECTED".equals(stageStatus)) {
            Set<String> reject = def.stage(d.stage).map(StageDefinition::rejectEvents).orElse(Set.of());
            if (d.lines.stream().anyMatch(l -> l.getEventAction() != null && reject.contains(l.getEventAction()))) {
                return "REJECTED";
            }
        }
        if (d.lines.stream().anyMatch(TraceEventLinkEntity::isWarning)) {
            return "WARN";
        }
        return "OK";
    }

    private static String label(Draft d) {
        TraceEventLinkEntity first = d.lines.get(0);
        switch (d.kind) {
            case "ACTOR": {
                String user = d.lines.stream().map(TraceEventLinkEntity::getUserId).filter(Objects::nonNull).findFirst().orElse("unknown user");
                String session = d.lines.stream().map(TraceEventLinkEntity::getSessionId).filter(Objects::nonNull).findFirst().orElse(null);
                if (d.key.contains("|out|")) {
                    boolean viaHttp = d.lines.stream().anyMatch(l -> l.getHttpStatus() != null);
                    String action = d.lines.stream().map(TraceEventLinkEntity::getEventAction).filter(Objects::nonNull)
                            .filter(a -> !a.contains("/")).findFirst().orElse("ended");
                    return user + (viaHttp ? " sign-out" : " session ended (" + action + ")");
                }
                return user + " sign-in" + (session != null ? " (" + abbreviate(session) + ")" : "");
            }
            case "SERVICE": {
                Optional<TraceEventLinkEntity> http = d.lines.stream().filter(l -> l.getHttpStatus() != null).findFirst();
                if (http.isPresent()) {
                    String path = Objects.toString(http.get().getUrlPath(), "");
                    String tail = path.isEmpty() ? "" : path.substring(path.lastIndexOf('/'));
                    return d.service + " " + http.get().getHttpMethod() + " " + tail + " → " + http.get().getHttpStatus();
                }
                String action = d.lines.stream().map(TraceEventLinkEntity::getEventAction).filter(Objects::nonNull).findFirst().orElse(null);
                return d.service + (action != null ? " " + action : "");
            }
            case "COMPONENT":
                return d.logger.substring(d.logger.lastIndexOf('.') + 1);
            case "EXTERNAL": {
                String extId = d.key.substring(d.key.lastIndexOf('|') + 1);
                String status = d.lines.stream().map(TraceEventLinkEntity::getProviderStatus).filter(Objects::nonNull).findFirst().orElse(null);
                return "External " + extId + (status != null ? ", " + status : "");
            }
            default:
                return first.getService();
        }
    }

    private static String handoffLabel(Draft source, Draft target) {
        Set<String> sessions = new LinkedHashSet<>();
        source.lines.stream().map(TraceEventLinkEntity::getSessionId).filter(Objects::nonNull).forEach(sessions::add);
        Optional<String> shared = target.lines.stream().map(TraceEventLinkEntity::getSessionId).filter(sessions::contains).findFirst();
        if (shared.isPresent()) {
            return "session " + abbreviate(shared.get());
        }
        Set<String> users = new LinkedHashSet<>();
        source.lines.stream().map(TraceEventLinkEntity::getUserId).filter(Objects::nonNull).forEach(users::add);
        Optional<String> sharedUser = target.lines.stream().map(TraceEventLinkEntity::getUserId).filter(users::contains).findFirst();
        return sharedUser.map(u -> "user " + u).orElse("next stage");
    }

    private static String first(Draft d) {
        return d.lines.get(0).getEsDocId();
    }

    private static String last(Draft d) {
        return d.lines.get(d.lines.size() - 1).getEsDocId();
    }

    static String abbreviate(String id) {
        int keep = id.startsWith("sess_") ? 9 : 4;
        return id.length() <= keep ? id : id.substring(0, keep) + "…";
    }
}
