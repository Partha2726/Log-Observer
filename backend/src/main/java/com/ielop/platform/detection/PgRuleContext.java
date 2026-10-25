package com.ielop.platform.detection;

import com.ielop.platform.flowdef.FlowDefinition;
import com.ielop.platform.flowdef.FlowDefinitionRegistry;
import com.ielop.platform.logsearch.LogCriteria;
import com.ielop.platform.logsearch.LogFields;
import com.ielop.platform.logsearch.LogHit;
import com.ielop.platform.logsearch.LogQueryService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@link RuleContext} over Postgres (traces, user limits) and Elasticsearch through logsearch. */
@Component
@EnableConfigurationProperties(RulesProperties.class)
public class PgRuleContext implements RuleContext {

    private final NamedParameterJdbcTemplate jdbc;
    private final LogQueryService logs;
    private final RulesProperties rules;
    private final FlowDefinitionRegistry flows;

    public PgRuleContext(NamedParameterJdbcTemplate jdbc, LogQueryService logs, RulesProperties rules, FlowDefinitionRegistry flows) {
        this.jdbc = jdbc;
        this.logs = logs;
        this.rules = rules;
        this.flows = flows;
    }

    @Override
    public RulesProperties rules() {
        return rules;
    }

    @Override
    public Optional<BigDecimal> userLimit(String userId) {
        return jdbc.queryForList("SELECT limit_amount FROM user_limit WHERE user_id = :u", new MapSqlParameterSource("u", userId),
                BigDecimal.class).stream().findFirst();
    }

    @Override
    public List<Submission> sameSubmissions(String flowId, String requester, String counterpartyHash, BigDecimal amount,
                                            Instant from, Instant to, long excludeTraceId, String creationStage,
                                            Collection<String> creationSuccessActions) {
        var p = new MapSqlParameterSource().addValue("flow", flowId).addValue("req", requester).addValue("hash", counterpartyHash)
                .addValue("amount", amount).addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to))
                .addValue("ex", excludeTraceId).addValue("stage", creationStage).addValue("actions", List.copyOf(creationSuccessActions));
        List<Submission> out = new ArrayList<>();
        jdbc.query("""
                SELECT t.id, t.flow_ref, t.first_seen,
                       ARRAY(SELECT l.es_doc_id FROM trace_event_link l WHERE l.trace_id = t.id AND l.stage = :stage
                             AND l.event_action IN (:actions) ORDER BY l.seq) AS docs
                  FROM trace t
                 WHERE t.flow_id = :flow AND t.requester_user_id = :req AND t.counterparty_hash = :hash AND t.amount = :amount
                   AND t.first_seen BETWEEN :from AND :to AND t.id <> :ex
                 ORDER BY t.first_seen, t.flow_ref""", p, rs -> {
            String[] docs = (String[]) rs.getArray("docs").getArray();
            if (docs.length > 0) {
                out.add(new Submission(rs.getLong("id"), rs.getString("flow_ref"), rs.getTimestamp("first_seen").toInstant(), List.of(docs)));
            }
        });
        return out;
    }

    @Override
    public Optional<Long> stageDurationP99(String flowId, String stage, Instant from, Instant to) {
        var p = new MapSqlParameterSource().addValue("flow", flowId).addValue("stage", stage)
                .addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to));
        return jdbc.query("""
                SELECT count(*) AS n,
                       percentile_disc(0.99) WITHIN GROUP (ORDER BY EXTRACT(EPOCH FROM (s.ended_at - s.started_at)) * 1000) AS p99
                  FROM trace_stage s JOIN trace t ON t.id = s.trace_id
                 WHERE t.flow_id = :flow AND s.stage = :stage AND s.status = 'COMPLETED'
                   AND s.started_at >= :from AND s.started_at < :to""", p, rs -> {
            rs.next();
            double v = rs.getDouble("p99");
            return rs.getLong("n") < rules.getSlowStageMinSamples() || rs.wasNull() ? Optional.<Long>empty() : Optional.of((long) v);
        });
    }

    @Override
    public List<LogHit> linesWithActions(Collection<String> actions, Instant from, Instant to, int cap) {
        return logs.findAll("rules.actions", LogCriteria.builder().eventActions(actions).between(from, to).build(), cap).hits();
    }

    @Override
    public List<FlowDefinition> definitions() {
        return flows.all();
    }

    @Override
    public List<StuckTrace> stuckTraces(FlowDefinition def, String creationStage, String approvalStage, String finalStage,
                                        Collection<String> creationSuccessActions, Instant from, Instant to) {
        var p = new MapSqlParameterSource().addValue("flow", def.id()).addValue("create", creationStage)
                .addValue("approve", approvalStage).addValue("final", finalStage)
                .addValue("actions", List.copyOf(creationSuccessActions))
                .addValue("from", Timestamp.from(from)).addValue("to", Timestamp.from(to));
        List<StuckTrace> out = new ArrayList<>();
        jdbc.query("""
                SELECT t.id, t.flow_ref, t.requester_user_id, t.first_seen, a.status AS approval_status,
                       ARRAY(SELECT l.es_doc_id FROM trace_event_link l WHERE l.trace_id = t.id AND l.stage = :create
                             AND l.event_action IN (:actions) ORDER BY l.seq) AS docs
                  FROM trace t
                  JOIN trace_stage c ON c.trace_id = t.id AND c.stage = :create AND c.status = 'COMPLETED'
                  JOIN trace_stage a ON a.trace_id = t.id AND a.stage = :approve AND a.status NOT IN ('COMPLETED', 'REJECTED')
                  JOIN trace_stage f ON f.trace_id = t.id AND f.stage = :final AND f.status = 'NOT_REACHED'
                 WHERE t.flow_id = :flow AND t.first_seen BETWEEN :from AND :to
                 ORDER BY t.first_seen, t.flow_ref""", p, rs -> {
            String[] docs = (String[]) rs.getArray("docs").getArray();
            if (docs.length > 0) {
                out.add(new StuckTrace(rs.getLong("id"), rs.getString("flow_ref"), rs.getString("requester_user_id"),
                        rs.getTimestamp("first_seen").toInstant(), rs.getString("approval_status"), List.of(docs)));
            }
        });
        return out;
    }

    @Override
    public List<LogHit> sessionLines(Collection<String> sessionIds, Collection<String> actions, Instant from, Instant to, int cap) {
        List<String> all = List.copyOf(sessionIds);
        List<LogHit> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i += 1000) {
            out.addAll(logs.findAll("rules.sessions", LogCriteria.builder().sessionIds(all.subList(i, Math.min(all.size(), i + 1000)))
                    .eventActions(actions).between(from, to).build(), cap).hits());
        }
        return out;
    }

    @Override
    public Map<String, Long> ipsWithStatuses(Collection<Integer> statuses, Instant from, Instant to, int size) {
        return logs.terms("rules.ips", LogCriteria.builder().httpStatuses(statuses).between(from, to).build(), LogFields.CLIENT_IP, size);
    }

    @Override
    public Map<String, Long> userAgents(Instant from, Instant to, int size) {
        return logs.terms("rules.agents", LogCriteria.builder().hasHttpStatus().between(from, to).build(), LogFields.USER_AGENT, size);
    }

    @Override
    public List<LogHit> httpLinesOfIps(Collection<String> ips, Instant from, Instant to, int cap) {
        return logs.findAll("rules.ip-lines", LogCriteria.builder().clientIps(ips).hasHttpStatus().between(from, to).build(), cap).hits();
    }

    @Override
    public List<LogHit> httpLinesWithAgents(Collection<String> agents, Instant from, Instant to, int cap) {
        return logs.findAll("rules.agent-lines", LogCriteria.builder().userAgents(agents).hasHttpStatus().between(from, to).build(), cap).hits();
    }
}
