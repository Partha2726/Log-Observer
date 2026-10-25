package com.ielop.platform.detection;

import com.ielop.platform.flowdef.FlowDefinition;
import com.ielop.platform.logsearch.LogHit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Predicate;

/** A rule context with fixed answers for hand-written rule tests; log lookups filter {@link #lines}. */
final class FakeRuleContext implements RuleContext {

    final RulesProperties props = new RulesProperties();
    final Map<String, BigDecimal> limits = new LinkedHashMap<>();
    final List<Submission> submissions = new ArrayList<>();
    final List<StuckTrace> stuck = new ArrayList<>();
    final List<LogHit> lines = new ArrayList<>();
    final List<FlowDefinition> definitions = new ArrayList<>(List.of(TraceRulesTest.DEMO));
    Long p99;

    @Override
    public RulesProperties rules() {
        return props;
    }

    @Override
    public Optional<BigDecimal> userLimit(String userId) {
        return Optional.ofNullable(limits.get(userId));
    }

    @Override
    public List<Submission> sameSubmissions(String flowId, String requester, String counterpartyHash, BigDecimal amount,
                                            Instant from, Instant to, long excludeTraceId, String creationStage,
                                            Collection<String> creationSuccessActions) {
        return submissions.stream().filter(s -> !s.firstSeen().isBefore(from) && !s.firstSeen().isAfter(to)).toList();
    }

    @Override
    public Optional<Long> stageDurationP99(String flowId, String stage, Instant from, Instant to) {
        return Optional.ofNullable(p99);
    }

    @Override
    public List<FlowDefinition> definitions() {
        return definitions;
    }

    @Override
    public List<StuckTrace> stuckTraces(FlowDefinition def, String creationStage, String approvalStage, String finalStage,
                                        Collection<String> creationSuccessActions, Instant from, Instant to) {
        return stuck.stream().filter(s -> !s.firstSeen().isBefore(from) && !s.firstSeen().isAfter(to)).toList();
    }

    private List<LogHit> select(Instant from, Instant to, Predicate<LogHit> p) {
        return lines.stream().filter(h -> !h.timestamp().isBefore(from) && !h.timestamp().isAfter(to)).filter(p).toList();
    }

    @Override
    public List<LogHit> linesWithActions(Collection<String> actions, Instant from, Instant to, int cap) {
        return select(from, to, h -> actions.contains(h.eventAction()));
    }

    @Override
    public List<LogHit> sessionLines(Collection<String> sessionIds, Collection<String> actions, Instant from, Instant to, int cap) {
        return select(from, to, h -> sessionIds.contains(h.sessionId()) && actions.contains(h.eventAction()));
    }

    @Override
    public Map<String, Long> ipsWithStatuses(Collection<Integer> statuses, Instant from, Instant to, int size) {
        Map<String, Long> out = new TreeMap<>();
        select(from, to, h -> h.httpStatus() != null && statuses.contains(h.httpStatus()) && h.clientIp() != null)
                .forEach(h -> out.merge(h.clientIp(), 1L, Long::sum));
        return out;
    }

    @Override
    public Map<String, Long> userAgents(Instant from, Instant to, int size) {
        Map<String, Long> out = new TreeMap<>();
        select(from, to, h -> h.httpStatus() != null && h.userAgent() != null).forEach(h -> out.merge(h.userAgent(), 1L, Long::sum));
        return out;
    }

    @Override
    public List<LogHit> httpLinesOfIps(Collection<String> ips, Instant from, Instant to, int cap) {
        return select(from, to, h -> h.httpStatus() != null && ips.contains(h.clientIp()));
    }

    @Override
    public List<LogHit> httpLinesWithAgents(Collection<String> agents, Instant from, Instant to, int cap) {
        return select(from, to, h -> h.httpStatus() != null && agents.contains(h.userAgent()));
    }

    /** A log line with the fields scan rules read. */
    static LogHit hit(String id, Instant ts, String userId, String sessionId, String action, String ip, Integer status,
                      String userAgent, String resource) {
        return new LogHit(id, "logs-flow-test", ts, "svc", "host-1", "D", "INFO", null, null, null, null, List.of(), userId,
                sessionId, null, action, ip, null, null, status, null, userAgent, null, null, false, false, false,
                "/logs/svc/test.log", 0, ts, null, null, null, null, null, null, null, resource, null, List.of(), null, null, List.of(), null, null);
    }
}
