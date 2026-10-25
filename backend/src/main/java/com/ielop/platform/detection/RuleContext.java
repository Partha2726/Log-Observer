package com.ielop.platform.detection;

import com.ielop.platform.flowdef.FlowDefinition;
import com.ielop.platform.logsearch.LogHit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Data rules may look up beyond the trace itself (Postgres, and Elasticsearch through logsearch only). */
public interface RuleContext {

    RulesProperties rules();

    /** R10: the user's approval limit from {@code user_limit}. */
    Optional<BigDecimal> userLimit(String userId);

    /** A submission R11 compares with: another trace's flowRef, first sight and its creation evidence. */
    record Submission(long traceId, String flowRef, Instant firstSeen, List<String> creationDocIds) {
    }

    /** R11: other traces of the same requester, counterparty and amount first seen within the window. */
    List<Submission> sameSubmissions(String flowId, String requester, String counterpartyHash, BigDecimal amount,
                                     Instant from, Instant to, long excludeTraceId, String creationStage,
                                     Collection<String> creationSuccessActions);

    /** R09: p99 duration (ms) of a stage's completed instances started in the window; empty below {@code slowStageMinSamples}. */
    Optional<Long> stageDurationP99(String flowId, String stage, Instant from, Instant to);

    /** The loaded flow definitions (scan rules that work per flow, R06). */
    List<FlowDefinition> definitions();

    /** A trace R06 reports: creation completed, no approval yet, nothing executed. */
    record StuckTrace(long traceId, String flowRef, String requester, Instant firstSeen, String approvalStatus,
                      List<String> creationDocIds) {
    }

    /**
     * R06: traces of {@code def} first seen in {@code [from, to]} whose creation stage completed, whose approval
     * stage is neither completed nor rejected, and whose final stage was not reached.
     */
    List<StuckTrace> stuckTraces(FlowDefinition def, String creationStage, String approvalStage, String finalStage,
                                 Collection<String> creationSuccessActions, Instant from, Instant to);

    /** Scan rules: lines with one of the event actions in the window, oldest first. */
    List<LogHit> linesWithActions(Collection<String> actions, Instant from, Instant to, int cap);

    /** R08: lines of the given sessions with one of the event actions (session ends), oldest first. */
    List<LogHit> sessionLines(Collection<String> sessionIds, Collection<String> actions, Instant from, Instant to, int cap);

    /** R15: client IPs with their number of HTTP lines answered with one of {@code statuses}, most first. */
    Map<String, Long> ipsWithStatuses(Collection<Integer> statuses, Instant from, Instant to, int size);

    /** R15: distinct user agents of HTTP lines with their counts. */
    Map<String, Long> userAgents(Instant from, Instant to, int size);

    /** R15: HTTP lines from the given client IPs, oldest first. */
    List<LogHit> httpLinesOfIps(Collection<String> ips, Instant from, Instant to, int cap);

    /** R15: HTTP lines with one of the exact user agents, oldest first. */
    List<LogHit> httpLinesWithAgents(Collection<String> agents, Instant from, Instant to, int cap);
}
