package com.ielop.platform.trace.assembly;

import com.ielop.platform.flowdef.FlowDefinition;
import com.ielop.platform.flowdef.StageDefinition;
import com.ielop.platform.logsearch.HitList;
import com.ielop.platform.logsearch.LogCriteria.TimeRange;
import com.ielop.platform.logsearch.LogHit;
import com.ielop.platform.trace.TraceProperties;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Rebuilds one flow from its log lines (spec section 7.1, steps 1 to 12), driven
 * entirely by its flow definition. Deterministic: the same lines and configuration
 * give the same result whatever order the evidence source returns them in.
 *
 * <p>Confidence (section 7.4): 1.0 for OBSERVED lines (flowRef, request ID, session);
 * 0.8 for a same-host same-thread line within the thread window, 0.5 when another
 * flow has an OBSERVED line on that thread in the window; 0.7 for a sign-in found by
 * the last-sign-in-before fallback; 0.6 / k for time-only links. A line is linked
 * only at or above {@code minConfidence}; the highest route wins.</p>
 *
 * <p>Stage status rests on OBSERVED lines only (TRACE-014), so a CORRELATED line can
 * never flip a stage. The first failure may be a CORRELATED error line when its link
 * is unambiguous (confidence at least 0.8), which is how a legacy-service exception
 * that lost its request ID is still named as the failing component.</p>
 */
public final class Assembler {

    /**
     * 2: denied attempts (section 7.1 addition, docs/DECISIONS.md) no longer shape stages, actors or windows.
     * 3: OpenTelemetry trace IDs link lines as OBSERVED (link reason TRACE, phase 3).
     */
    public static final int ASSEMBLY_VERSION = 3;

    static final BigDecimal ONE = new BigDecimal("1.00");
    static final BigDecimal THREAD = new BigDecimal("0.80");
    static final BigDecimal THREAD_AMBIGUOUS = new BigDecimal("0.50");
    static final BigDecimal FALLBACK = new BigDecimal("0.70");
    static final BigDecimal TIME_BASE = new BigDecimal("0.60");

    /** Trace order (section 7.1 step 12): timestamp, file path, offset; the document ID breaks any remaining tie. */
    public static final Comparator<LogHit> ORDER = Comparator.comparing(LogHit::timestamp, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(h -> Objects.toString(h.filePath(), ""))
            .thenComparingLong(LogHit::offset)
            .thenComparing(LogHit::id);

    /** Event actions of a permission denial, from the log contract (section 3). */
    public static final Set<String> DEFAULT_DENIED_ACTIONS = Set.of("AUTHZ_DENIED");
    static final int DENIED_HTTP_STATUS = 403;

    private final TraceProperties props;
    private final Pattern noiseThreads;
    private final Set<String> deniedActions;

    public Assembler(TraceProperties props) {
        this(props, DEFAULT_DENIED_ACTIONS);
    }

    public Assembler(TraceProperties props, Set<String> deniedActions) {
        this.props = props;
        this.noiseThreads = Pattern.compile(props.noiseThreads());
        this.deniedActions = Set.copyOf(deniedActions);
    }

    /** A line linked so far, with the OBSERVED lines that pulled it in (for CORRELATED staging). */
    private static final class Candidate {
        final LogHit hit;
        EvidenceClass cls;
        BigDecimal confidence;
        LinkReason reason;
        String stage;
        LogHit nearestSeed;
        /** Part of a request that was refused (HTTP 403 or a permission-denial action): kept as evidence, never shapes the trace. */
        boolean denied;

        Candidate(LogHit hit, EvidenceClass cls, BigDecimal confidence, LinkReason reason) {
            this.hit = hit;
            this.cls = cls;
            this.confidence = confidence;
            this.reason = reason;
        }
    }

    public AssemblyResult assemble(String flowRef, FlowDefinition def, TraceEvidenceSource source) {
        Map<String, Candidate> linked = new TreeMap<>();

        // 1. Seed: every line carrying the flowRef.
        HitList seed = source.seed(flowRef);
        if (seed.hits().isEmpty()) {
            throw new TraceNotFoundException(flowRef);
        }
        sorted(seed.hits()).forEach(h -> observe(linked, h, LinkReason.ID));

        // 2. Request expansion, one hop: request IDs on the seed lines only.
        Set<String> ourRequestIds = new TreeSet<>();
        seed.hits().stream().map(LogHit::requestId).filter(Objects::nonNull).forEach(ourRequestIds::add);
        if (!ourRequestIds.isEmpty()) {
            sorted(source.byRequestIds(ourRequestIds)).forEach(h -> observe(linked, h, LinkReason.REQUEST));
        }

        // Denied attempts (someone without the permission calling a stage's endpoint with this flowRef) stay
        // linked as evidence but do not pull in sessions, shape stage windows, actors or status.
        markDenied(linked.values());

        // 2b (phase 3). OpenTelemetry: a trace ID on a line of the flow is an exact key for the whole flow, so
        // every line with that trace ID is OBSERVED (link reason TRACE), including legacy lines without a request ID.
        Set<String> traceIds = new TreeSet<>();
        observed(linked).stream().map(LogHit::traceId).filter(Objects::nonNull).forEach(traceIds::add);
        if (!traceIds.isEmpty()) {
            sorted(source.byTraceIds(traceIds)).forEach(h -> observe(linked, h, LinkReason.TRACE));
            markDenied(linked.values());
        }

        // 3. Session expansion: each actor's sign-in, sign-out or expiry, plus the gateway lines of those calls.
        Set<String> sessions = new TreeSet<>();
        observed(linked).stream().map(LogHit::sessionId).filter(Objects::nonNull).forEach(sessions::add);
        Set<String> sessionActions = def.sessionEventActions();
        if (!sessions.isEmpty() && !sessionActions.isEmpty()) {
            List<LogHit> sessionLines = sorted(source.sessionEvents(sessions, sessionActions));
            sessionLines.forEach(h -> observe(linked, h, LinkReason.SESSION));
            Set<String> sessionRequestIds = new TreeSet<>();
            sessionLines.stream().map(LogHit::requestId).filter(Objects::nonNull)
                    .filter(r -> !ourRequestIds.contains(r)).forEach(sessionRequestIds::add);
            if (!sessionRequestIds.isEmpty()) {
                sorted(source.byRequestIds(sessionRequestIds)).forEach(h -> observe(linked, h, LinkReason.SESSION));
                ourRequestIds.addAll(sessionRequestIds);
            }
        }

        markDenied(linked.values());

        // 4. Stage assignment of OBSERVED lines: matcher, then request ID, then (below) stage windows.
        assignByDefinition(def, linked.values());
        inheritByRequestId(linked.values());
        inheritBySpanId(linked.values());

        // 3 (fallback). An actor with no matched sign-in: their last successful sign-in before their first line.
        Map<String, String> actors = actors(def, linked.values());
        sessionFallback(def, linked, actors.values(), source);

        // 5. Stage windows per (stage, host), from OBSERVED staged lines.
        Map<String, Map<String, Instant[]>> windows = windows(def, linked.values());
        for (Candidate c : sortedCandidates(linked.values())) {
            if (c.stage == null) {
                c.stage = stageFromWindows(windows, c.hit, def);
            }
        }

        // 6 and 7. Thread and time-only expansion (CORRELATED), 8. noise filter and threshold.
        Map<String, Candidate> correlated = new TreeMap<>();
        int excluded = threadExpansion(flowRef, linked, ourRequestIds, source, correlated);
        excluded += timeOnlyExpansion(flowRef, linked, ourRequestIds, source, correlated);
        for (Candidate c : correlated.values()) {
            linked.put(c.hit.id(), c);
        }
        assignByDefinition(def, correlated.values());
        for (Candidate c : sortedCandidates(correlated.values())) {
            if (c.stage == null) {
                c.stage = c.nearestSeed != null ? stageOf(linked, c.nearestSeed) : stageFromWindows(windows, c.hit, def);
            }
        }

        markDenied(linked.values());

        // 12. Trace order.
        List<Candidate> ordered = sortedCandidates(linked.values());
        List<TraceLink> links = new ArrayList<>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            Candidate c = ordered.get(i);
            links.add(new TraceLink(c.hit, c.stage, c.cls, c.confidence, c.reason, i));
        }

        // 9 to 11. Actors, stage status, first failure.
        actors = actors(def, linked.values());
        Set<String> deniedIds = new TreeSet<>();
        linked.values().stream().filter(c -> c.denied).forEach(c -> deniedIds.add(c.hit.id()));
        List<TraceLink> shaping = links.stream().filter(l -> !deniedIds.contains(l.hit().id())).toList();
        List<StageResult> stages = stageResults(def, shaping, actors);
        String overall = overallStatus(def, stages);
        FirstFailure firstFailure = "FAILED".equals(overall) ? firstFailure(stages, shaping) : null;

        // The trace's time span comes from the lines that shape it; denied attempts can come hours later.
        List<TraceLink> span = shaping.isEmpty() ? links : shaping;
        long observedCount = links.stream().filter(l -> l.evidenceClass() == EvidenceClass.OBSERVED).count();
        BigDecimal ratio = BigDecimal.valueOf(observedCount).divide(BigDecimal.valueOf(links.size()), 2, RoundingMode.HALF_UP);
        Optional<LogHit> business = links.stream().filter(l -> l.evidenceClass() == EvidenceClass.OBSERVED)
                .map(TraceLink::hit).filter(h -> h.amount() != null).findFirst();
        Optional<LogHit> counterparty = links.stream().filter(l -> l.evidenceClass() == EvidenceClass.OBSERVED)
                .map(TraceLink::hit).filter(h -> h.counterpartyHash() != null).findFirst();

        return new AssemblyResult(flowRef, def.id(), def.version(), def.roles(), List.copyOf(links), stages, actors, overall,
                firstFailure, span.get(0).hit().timestamp(), span.get(span.size() - 1).hit().timestamp(),
                business.map(LogHit::amount).orElse(null),
                business.map(LogHit::currency).orElse(null),
                counterparty.map(LogHit::counterpartyHash).orElse(null),
                counterparty.map(LogHit::counterpartyLast4).orElse(null),
                ratio, excluded, seed.truncated(), ConfigHash.of(ASSEMBLY_VERSION, props, def));
    }

    // ------------------------------------------------------------- steps 1-3

    /** Marks every line of a refused request; a line without a request ID is refused when it is itself a denial. */
    private void markDenied(Collection<Candidate> candidates) {
        Set<String> deniedRequests = new TreeSet<>();
        for (Candidate c : candidates) {
            if (refusal(c.hit) && c.hit.requestId() != null) {
                deniedRequests.add(c.hit.requestId());
            }
        }
        for (Candidate c : candidates) {
            c.denied = c.hit.requestId() != null ? deniedRequests.contains(c.hit.requestId()) : refusal(c.hit);
        }
    }

    private boolean refusal(LogHit h) {
        return (h.httpStatus() != null && h.httpStatus() == DENIED_HTTP_STATUS) || (h.eventAction() != null && deniedActions.contains(h.eventAction()));
    }

    private static void observe(Map<String, Candidate> linked, LogHit hit, LinkReason reason) {
        linked.putIfAbsent(hit.id(), new Candidate(hit, EvidenceClass.OBSERVED, ONE, reason));
    }

    private void sessionFallback(FlowDefinition def, Map<String, Candidate> linked, Collection<String> actorUsers,
                                 TraceEvidenceSource source) {
        Set<String> success = def.signInSuccessEvents();
        Optional<StageDefinition> sessionStage = def.stages().stream().filter(StageDefinition::isSessionStage).findFirst();
        if (success.isEmpty() || sessionStage.isEmpty()) {
            return;
        }
        for (String user : new TreeSet<>(actorUsers)) {
            boolean signedIn = observed(linked).stream()
                    .anyMatch(h -> user.equals(h.userId()) && h.eventAction() != null && success.contains(h.eventAction()));
            if (signedIn) {
                continue;
            }
            Optional<Instant> firstLine = observed(linked).stream().filter(h -> user.equals(h.userId()))
                    .map(LogHit::timestamp).filter(Objects::nonNull).min(Comparator.naturalOrder());
            if (firstLine.isEmpty()) {
                continue;
            }
            source.lastEventBefore(user, success, firstLine.get()).ifPresent(h -> {
                if (!linked.containsKey(h.id())) {
                    Candidate c = new Candidate(h, EvidenceClass.CORRELATED, FALLBACK, LinkReason.SESSION_FALLBACK);
                    c.stage = sessionStage.get().name();
                    linked.put(h.id(), c);
                }
            });
        }
    }

    // ---------------------------------------------------------------- step 4

    private static void assignByDefinition(FlowDefinition def, Collection<Candidate> candidates) {
        for (Candidate c : candidates) {
            if (c.stage != null) {
                continue;
            }
            LogHit h = c.hit;
            Optional<StageDefinition> stage = def.stageOf(h.service(), h.httpMethod(), h.urlPath(), h.eventAction());
            if (stage.isPresent()) {
                c.stage = stage.get().name();
            } else if (def.isSignOut(h.service(), h.httpMethod(), h.urlPath(), h.eventAction())) {
                c.stage = FlowDefinition.SIGN_OUT;
            }
        }
    }

    /** A line no matcher accepts inherits the stage of the first matched OBSERVED line with its request ID. */
    private static void inheritByRequestId(Collection<Candidate> candidates) {
        Map<String, String> stageByRequest = new HashMap<>();
        for (Candidate c : sortedCandidates(candidates)) {
            if (c.cls == EvidenceClass.OBSERVED && c.stage != null && c.hit.requestId() != null) {
                stageByRequest.putIfAbsent(c.hit.requestId(), c.stage);
            }
        }
        for (Candidate c : candidates) {
            if (c.stage == null && c.hit.requestId() != null) {
                c.stage = stageByRequest.get(c.hit.requestId());
            }
        }
    }

    /** Phase 3: an OpenTelemetry span is one stage, so a line still without a stage takes the stage of its span. */
    private static void inheritBySpanId(Collection<Candidate> candidates) {
        Map<String, String> stageBySpan = new HashMap<>();
        for (Candidate c : sortedCandidates(candidates)) {
            if (c.cls == EvidenceClass.OBSERVED && c.stage != null && c.hit.spanId() != null) {
                stageBySpan.putIfAbsent(c.hit.spanId(), c.stage);
            }
        }
        for (Candidate c : candidates) {
            if (c.stage == null && c.hit.spanId() != null) {
                c.stage = stageBySpan.get(c.hit.spanId());
            }
        }
    }

    // ---------------------------------------------------------------- step 5

    private Map<String, Map<String, Instant[]>> windows(FlowDefinition def, Collection<Candidate> candidates) {
        Map<String, Map<String, Instant[]>> windows = new LinkedHashMap<>();
        for (StageDefinition s : def.stages()) {
            windows.put(s.name(), new TreeMap<>());
        }
        Duration pad = Duration.ofMillis(props.windowPadMs());
        for (Candidate c : candidates) {
            if (c.cls != EvidenceClass.OBSERVED || c.denied || c.stage == null || !windows.containsKey(c.stage) || c.hit.timestamp() == null) {
                continue;
            }
            String host = Objects.toString(c.hit.host(), "");
            Instant ts = c.hit.timestamp();
            windows.get(c.stage).merge(host, new Instant[]{ts.minus(pad), ts.plus(pad)},
                    (a, b) -> new Instant[]{a[0].isBefore(b[0]) ? a[0] : b[0], a[1].isAfter(b[1]) ? a[1] : b[1]});
        }
        return windows;
    }

    /** The stage whose window on the line's host contains it; else any host's window; else the nearest window. */
    private static String stageFromWindows(Map<String, Map<String, Instant[]>> windows, LogHit hit, FlowDefinition def) {
        Instant ts = hit.timestamp();
        String host = Objects.toString(hit.host(), "");
        for (var e : windows.entrySet()) {
            Instant[] w = e.getValue().get(host);
            if (w != null && !ts.isBefore(w[0]) && !ts.isAfter(w[1])) {
                return e.getKey();
            }
        }
        String best = null;
        long bestDistance = Long.MAX_VALUE;
        for (var e : windows.entrySet()) {
            for (Instant[] w : e.getValue().values()) {
                long d = ts.isBefore(w[0]) ? Duration.between(ts, w[0]).toMillis()
                        : ts.isAfter(w[1]) ? Duration.between(w[1], ts).toMillis() : 0;
                if (d < bestDistance) {
                    bestDistance = d;
                    best = e.getKey();
                }
            }
        }
        return best != null ? best : def.stages().get(0).name();
    }

    // ---------------------------------------------------------- steps 6 to 8

    private int threadExpansion(String flowRef, Map<String, Candidate> linked, Set<String> ourRequestIds,
                                TraceEvidenceSource source, Map<String, Candidate> out) {
        Map<String, List<LogHit>> seedsByThread = new TreeMap<>();
        for (LogHit h : observed(linked)) {
            if (h.thread() != null && h.host() != null && h.timestamp() != null) {
                seedsByThread.computeIfAbsent(h.host() + "\u0000" + h.thread(), k -> new ArrayList<>()).add(h);
            }
        }
        Duration window = Duration.ofMillis(props.threadWindowMs());
        Set<String> rejected = new TreeSet<>();
        for (var entry : seedsByThread.entrySet()) {
            List<LogHit> seeds = sorted(entry.getValue());
            String host = seeds.get(0).host();
            String thread = seeds.get(0).thread();
            List<LogHit> lines = sorted(source.onThread(host, thread, ranges(seeds, window)));
            Map<String, Set<String>> owners = owners(lines, ourRequestIds, source);
            for (LogHit c : lines) {
                if (linked.containsKey(c.id()) || !c.flowRefs().isEmpty() || c.requestId() != null || isNoiseThread(c)) {
                    continue;
                }
                LogHit nearest = nearest(seeds, c, window);
                if (nearest == null) {
                    continue;
                }
                boolean competing = lines.stream().anyMatch(x -> within(x, c, window) && belongsToOtherFlow(x, flowRef, owners));
                BigDecimal confidence = competing ? THREAD_AMBIGUOUS : THREAD;
                consider(out, rejected, c, confidence, LinkReason.THREAD, nearest);
            }
        }
        rejected.removeAll(out.keySet());
        return rejected.size();
    }

    private int timeOnlyExpansion(String flowRef, Map<String, Candidate> linked, Set<String> ourRequestIds,
                                  TraceEvidenceSource source, Map<String, Candidate> out) {
        Map<String, List<LogHit>> seedsByHost = new TreeMap<>();
        for (LogHit h : observed(linked)) {
            if (h.host() != null && h.timestamp() != null) {
                seedsByHost.computeIfAbsent(h.host(), k -> new ArrayList<>()).add(h);
            }
        }
        Duration window = Duration.ofMillis(props.timeOnlyWindowMs());
        Set<String> rejected = new TreeSet<>();
        for (var entry : seedsByHost.entrySet()) {
            List<LogHit> seeds = sorted(entry.getValue());
            List<LogHit> lines = sorted(source.onHost(entry.getKey(), ranges(seeds, window)));
            List<LogHit> candidates = lines.stream().filter(Assembler::hasNoThreadAndNoId)
                    .filter(c -> !linked.containsKey(c.id())).toList();
            if (candidates.isEmpty()) {
                continue;
            }
            Map<String, Set<String>> owners = owners(lines, ourRequestIds, source);
            for (LogHit c : candidates) {
                LogHit nearest = nearest(seeds, c, window);
                if (nearest == null) {
                    continue;
                }
                Set<String> flows = new TreeSet<>();
                flows.add(flowRef);
                for (LogHit x : lines) {
                    if (within(x, c, window)) {
                        flows.addAll(flowsOf(x, owners));
                    }
                }
                BigDecimal confidence = TIME_BASE.divide(BigDecimal.valueOf(flows.size()), 2, RoundingMode.HALF_UP);
                consider(out, rejected, c, confidence, LinkReason.TIME, nearest);
            }
        }
        rejected.removeAll(out.keySet());
        return rejected.size();
    }

    private void consider(Map<String, Candidate> out, Set<String> rejected, LogHit hit, BigDecimal confidence,
                          LinkReason reason, LogHit nearestSeed) {
        if (confidence.doubleValue() + 1e-9 < props.minConfidence()) {
            rejected.add(hit.id());
            return;
        }
        Candidate existing = out.get(hit.id());
        if (existing == null || confidence.compareTo(existing.confidence) > 0) {
            Candidate c = new Candidate(hit, EvidenceClass.CORRELATED, confidence, reason);
            c.nearestSeed = nearestSeed;
            out.put(hit.id(), c);
        }
    }

    private boolean isNoiseThread(LogHit h) {
        return h.thread() != null && noiseThreads.matcher(h.thread()).find();
    }

    private static boolean hasNoThreadAndNoId(LogHit h) {
        return h.thread() == null && h.requestId() == null && h.sessionId() == null && h.userId() == null && h.flowRefs().isEmpty();
    }

    /** Owners of the request IDs seen in a window that are not ours. */
    private static Map<String, Set<String>> owners(List<LogHit> lines, Set<String> ourRequestIds, TraceEvidenceSource source) {
        Set<String> others = new TreeSet<>();
        lines.stream().map(LogHit::requestId).filter(Objects::nonNull).filter(r -> !ourRequestIds.contains(r)).forEach(others::add);
        return others.isEmpty() ? Map.of() : source.flowRefsByRequestId(others);
    }

    private static Set<String> flowsOf(LogHit x, Map<String, Set<String>> owners) {
        Set<String> flows = new HashSet<>(x.flowRefs());
        if (x.requestId() != null) {
            flows.addAll(owners.getOrDefault(x.requestId(), Set.of()));
        }
        return flows;
    }

    private static boolean belongsToOtherFlow(LogHit x, String flowRef, Map<String, Set<String>> owners) {
        return flowsOf(x, owners).stream().anyMatch(f -> !f.equals(flowRef));
    }

    private static boolean within(LogHit a, LogHit b, Duration window) {
        return a.timestamp() != null && b.timestamp() != null
                && Duration.between(a.timestamp(), b.timestamp()).abs().compareTo(window) <= 0;
    }

    private static LogHit nearest(List<LogHit> seeds, LogHit c, Duration window) {
        LogHit best = null;
        long bestDistance = Long.MAX_VALUE;
        for (LogHit s : seeds) {
            long d = Duration.between(s.timestamp(), c.timestamp()).abs().toMillis();
            if (d <= window.toMillis() && d < bestDistance) {
                best = s;
                bestDistance = d;
            }
        }
        return best;
    }

    private static List<TimeRange> ranges(List<LogHit> seeds, Duration window) {
        List<TimeRange> merged = new ArrayList<>();
        Instant from = null;
        Instant to = null;
        for (LogHit s : seeds) {
            Instant f = s.timestamp().minus(window);
            Instant t = s.timestamp().plus(window);
            if (to != null && !f.isAfter(to)) {
                to = t.isAfter(to) ? t : to;
            } else {
                if (from != null) {
                    merged.add(new TimeRange(from, to));
                }
                from = f;
                to = t;
            }
        }
        if (from != null) {
            merged.add(new TimeRange(from, to));
        }
        return merged;
    }

    // --------------------------------------------------------- steps 9 to 11

    /** The user on each actor stage's OBSERVED lines (most frequent, then earliest), in role order. */
    private static Map<String, String> actors(FlowDefinition def, Collection<Candidate> candidates) {
        Map<String, String> actors = new LinkedHashMap<>();
        for (String role : def.roles()) {
            for (StageDefinition s : def.stages()) {
                if (!role.equals(s.actorRole())) {
                    continue;
                }
                Map<String, Integer> counts = new HashMap<>();
                Map<String, Integer> firstSeen = new HashMap<>();
                List<Candidate> lines = sortedCandidates(candidates).stream()
                        .filter(c -> c.cls == EvidenceClass.OBSERVED && !c.denied && s.name().equals(c.stage) && c.hit.userId() != null)
                        .toList();
                for (int i = 0; i < lines.size(); i++) {
                    String user = lines.get(i).hit.userId();
                    counts.merge(user, 1, Integer::sum);
                    firstSeen.putIfAbsent(user, i);
                }
                counts.entrySet().stream()
                        .min(Comparator.<Map.Entry<String, Integer>>comparingInt(e -> -e.getValue())
                                .thenComparingInt(e -> firstSeen.get(e.getKey())))
                        .ifPresent(e -> actors.putIfAbsent(role, e.getKey()));
            }
        }
        return actors;
    }

    private static List<StageResult> stageResults(FlowDefinition def, List<TraceLink> links, Map<String, String> actors) {
        List<StageResult> out = new ArrayList<>();
        int lastReached = 0;
        for (StageDefinition s : def.stages()) {
            if (links.stream().anyMatch(l -> s.name().equals(l.stage()))) {
                lastReached = s.order();
            }
        }
        for (StageDefinition s : def.stages()) {
            List<TraceLink> all = links.stream().filter(l -> s.name().equals(l.stage())).toList();
            List<LogHit> observed = all.stream().filter(l -> l.evidenceClass() == EvidenceClass.OBSERVED).map(TraceLink::hit).toList();
            StageStatus status = status(s, observed, all.isEmpty(), s.order() < lastReached);
            String user = s.actorRole() != null ? actors.get(s.actorRole())
                    : observed.stream().map(LogHit::userId).filter(Objects::nonNull).findFirst().orElse(null);
            out.add(new StageResult(s.name(), s.order(), status, user,
                    all.isEmpty() ? null : all.get(0).hit().timestamp(),
                    all.isEmpty() ? null : all.get(all.size() - 1).hit().timestamp(),
                    (int) all.stream().filter(l -> l.hit().isError()).count(), all.size()));
        }
        return List.copyOf(out);
    }

    /** Section 7.1 step 10, on the stage's OBSERVED lines (TRACE-014). */
    static StageStatus status(StageDefinition s, List<LogHit> observed, boolean noLines, boolean laterStageExists) {
        if (noLines) {
            return StageStatus.NOT_REACHED;
        }
        boolean success = observed.stream().anyMatch(h -> h.eventAction() != null && s.successEvents().contains(h.eventAction()));
        boolean error = observed.stream().anyMatch(LogHit::isError);
        Integer lastGatewayStatus = observed.stream().filter(h -> h.httpStatus() != null)
                .reduce((a, b) -> b).map(LogHit::httpStatus).orElse(null);
        if (error || (!success && lastGatewayStatus != null && lastGatewayStatus >= 400)) {
            return StageStatus.FAILED;
        }
        if (s.canReject() && observed.stream().anyMatch(h -> h.eventAction() != null && s.rejectEvents().contains(h.eventAction()))) {
            return StageStatus.REJECTED;
        }
        if (success || laterStageExists) {
            return StageStatus.COMPLETED;
        }
        return StageStatus.INCOMPLETE;
    }

    /** Section 2.5: FAILED wins (error lines are never hidden), then COMPLETED, REJECTED, INCOMPLETE. */
    static String overallStatus(FlowDefinition def, List<StageResult> stages) {
        if (stages.stream().anyMatch(s -> s.status() == StageStatus.FAILED)) {
            return "FAILED";
        }
        if (stages.get(stages.size() - 1).status() == StageStatus.COMPLETED) {
            return "COMPLETED";
        }
        boolean rejected = def.stages().stream().filter(StageDefinition::canReject)
                .anyMatch(d -> stages.stream().anyMatch(s -> s.name().equals(d.name()) && s.status() == StageStatus.REJECTED));
        return rejected ? "REJECTED" : "INCOMPLETE";
    }

    /**
     * The earliest error line of a failed stage, among OBSERVED lines and unambiguous
     * CORRELATED ones, preferring lines from an application component (with a logger);
     * when the stage failed only on its gateway status, that gateway line.
     */
    private static FirstFailure firstFailure(List<StageResult> stages, List<TraceLink> links) {
        TraceLink best = null;
        String bestStage = null;
        for (StageResult s : stages) {
            if (s.status() != StageStatus.FAILED) {
                continue;
            }
            List<TraceLink> inStage = links.stream().filter(l -> s.name().equals(l.stage())).toList();
            List<TraceLink> errors = inStage.stream().filter(l -> l.hit().isError())
                    .filter(l -> l.evidenceClass() == EvidenceClass.OBSERVED || l.confidence().compareTo(THREAD) >= 0)
                    .toList();
            // An application component's error (a line with a logger) beats a status-only line: the gateway's
            // 5xx is the consequence, and its access-log time has only second precision, so it can sort
            // before the exception that caused it.
            Optional<TraceLink> error = errors.stream().filter(l -> l.hit().logger() != null).findFirst()
                    .or(() -> errors.stream().findFirst());
            Optional<TraceLink> candidate = error.isPresent() ? error : inStage.stream()
                    .filter(l -> l.evidenceClass() == EvidenceClass.OBSERVED && l.hit().httpStatus() != null)
                    .reduce((a, b) -> b).filter(l -> l.hit().httpStatus() >= 400);
            if (candidate.isPresent() && (best == null || candidate.get().seq() < best.seq())) {
                best = candidate.get();
                bestStage = s.name();
            }
        }
        if (best == null) {
            return null;
        }
        LogHit h = best.hit();
        String component = h.logger() != null ? h.logger() : h.service();
        return new FirstFailure(bestStage, component, h.service(), h.errorType(), h.httpStatus(), h.id(), h.timestamp());
    }

    // --------------------------------------------------------------- helpers

    /** OBSERVED lines that shape the trace (denied attempts excluded). */
    private static List<LogHit> observed(Map<String, Candidate> linked) {
        return linked.values().stream().filter(c -> c.cls == EvidenceClass.OBSERVED && !c.denied).map(c -> c.hit).sorted(ORDER).toList();
    }

    private static String stageOf(Map<String, Candidate> linked, LogHit seed) {
        Candidate c = linked.get(seed.id());
        return c != null ? c.stage : null;
    }

    private static List<LogHit> sorted(Collection<LogHit> hits) {
        return hits.stream().sorted(ORDER).toList();
    }

    private static List<Candidate> sortedCandidates(Collection<Candidate> candidates) {
        return candidates.stream().sorted(Comparator.comparing(c -> c.hit, ORDER)).toList();
    }
}
