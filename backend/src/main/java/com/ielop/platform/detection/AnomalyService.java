package com.ielop.platform.detection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ielop.platform.common.PageResponse;
import com.ielop.platform.common.audit.AuditEventMessage;
import com.ielop.platform.common.audit.AuditPublisher;
import com.ielop.platform.common.exception.ApiException;
import com.ielop.platform.common.filter.RequestIdFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Anomaly list, detail and review (sections 8.8 and 10.2). Reviews follow the state
 * machine NEW → CONFIRMED | DISMISSED, and back to NEW only by an ADMINISTRATOR; any
 * other transition and a stale {@code version} are 409 {@code CONFLICT}.
 */
@Service
public class AnomalyService {

    public static final Set<String> STATUSES = Set.of("NEW", "CONFIRMED", "DISMISSED");

    /** Filters of {@code GET /api/anomalies}; null means "any". The range matches anomalies whose window overlaps it. */
    public record Filter(String ruleId, String severity, String status, String userId, String flowRef, String clientIp,
                         Instant from, Instant to) {
    }

    private final AnomalyRepository anomalies;
    private final NamedParameterJdbcTemplate jdbc;
    private final RuleEngine engine;
    private final AuditPublisher audit;
    private final ObjectProvider<AnomalyReviewHistory> history;
    private final ObjectMapper json;
    private final Clock clock = Clock.systemUTC();

    public AnomalyService(AnomalyRepository anomalies, NamedParameterJdbcTemplate jdbc, RuleEngine engine, AuditPublisher audit,
                          ObjectProvider<AnomalyReviewHistory> history, ObjectMapper json) {
        this.json = json;
        this.anomalies = anomalies;
        this.jdbc = jdbc;
        this.engine = engine;
        this.audit = audit;
        this.history = history;
    }

    @Transactional(readOnly = true)
    public PageResponse<AnomalyDtos.Item> list(Filter f, int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE TRUE");
        MapSqlParameterSource p = new MapSqlParameterSource();
        eq(where, p, "rule_id", "rule", f.ruleId());
        eq(where, p, "severity", "severity", f.severity());
        eq(where, p, "status", "status", f.status());
        eq(where, p, "user_id", "user", f.userId());
        eq(where, p, "flow_ref", "ref", f.flowRef());
        if (f.clientIp() != null) {
            where.append(" AND client_ip = CAST(:ip AS inet)");
            p.addValue("ip", f.clientIp());
        }
        if (f.from() != null) {
            where.append(" AND COALESCE(window_end, window_start, detected_at) >= :from");
            p.addValue("from", Timestamp.from(f.from()));
        }
        if (f.to() != null) {
            where.append(" AND COALESCE(window_start, detected_at) <= :to");
            p.addValue("to", Timestamp.from(f.to()));
        }
        long total = jdbc.queryForObject("SELECT count(*) FROM anomaly" + where, p, Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        Map<String, String> names = engine.ruleNames();
        List<AnomalyDtos.Item> items = jdbc.query("""
                SELECT id, rule_id, severity, status, flow_ref, user_id, host(client_ip) AS ip, detected_at, window_start,
                       window_end, details::text AS details, cardinality(evidence_doc_ids) AS evidence, version
                  FROM anomaly""" + where + " ORDER BY COALESCE(window_start, detected_at) DESC, id DESC LIMIT :limit OFFSET :offset",
                p, (rs, i) -> new AnomalyDtos.Item(rs.getLong("id"), rs.getString("rule_id"),
                        names.getOrDefault(rs.getString("rule_id"), rs.getString("rule_id")), rs.getString("severity"),
                        rs.getString("status"), rs.getString("flow_ref"), rs.getString("user_id"), rs.getString("ip"),
                        instant(rs.getTimestamp("detected_at")), instant(rs.getTimestamp("window_start")),
                        instant(rs.getTimestamp("window_end")), map(rs.getString("details")), rs.getInt("evidence"),
                        rs.getInt("version")));
        return PageResponse.of(items, page, size, total, false);
    }

    @Transactional(readOnly = true)
    public AnomalyDtos.Detail get(long id) {
        return detail(anomalies.findById(id).orElseThrow(() -> ApiException.notFound("No anomaly " + id)));
    }

    /** Applies one review; {@code administrator} may reopen. Audited as {@code ANOMALY_REVIEWED}. */
    @Transactional
    public AnomalyDtos.Detail review(long id, String status, String note, int version, String actor, boolean administrator) {
        AnomalyEntity a = anomalies.findById(id).orElseThrow(() -> ApiException.notFound("No anomaly " + id));
        if (a.getVersion() != version) {
            throw ApiException.conflict("Anomaly " + id + " was updated by someone else (version " + a.getVersion()
                    + ", not " + version + "); reload it");
        }
        String from = a.getStatus();
        boolean review = "NEW".equals(from) && ("CONFIRMED".equals(status) || "DISMISSED".equals(status));
        boolean reopen = !"NEW".equals(from) && "NEW".equals(status);
        if (!review && !reopen) {
            throw ApiException.conflict("An anomaly cannot go from " + from + " to " + status);
        }
        if (reopen && !administrator) {
            throw new ApiException("FORBIDDEN", HttpStatus.FORBIDDEN, "Only an administrator may reopen an anomaly");
        }
        Instant now = clock.instant();
        a.setStatus(status);
        a.setReviewedBy(actor);
        a.setReviewNote(note);
        a.setReviewedAt(now);
        try {
            anomalies.saveAndFlush(a);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw ApiException.conflict("Anomaly " + id + " was updated by someone else; reload it");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("ruleId", a.getRuleId());
        details.put("fromStatus", from);
        details.put("toStatus", status);
        details.put("note", note);
        audit.publish(new AuditEventMessage("ANOMALY_REVIEWED", actor, "anomaly", String.valueOf(id), details, now,
                RequestIdFilter.getCurrentRequestId(), null));
        return detail(a);
    }

    private AnomalyDtos.Detail detail(AnomalyEntity a) {
        AnomalyReviewHistory h = history.getIfAvailable();
        return new AnomalyDtos.Detail(a.getId(), a.getRuleId(), engine.ruleNames().getOrDefault(a.getRuleId(), a.getRuleId()),
                a.getSeverity(), a.getStatus(), a.getFlowRef(), a.getUserId(), a.getClientIp(), a.getDetectedAt(), a.getWindowStart(),
                a.getWindowEnd(), a.getDetails(), List.of(a.getEvidenceDocIds()), a.getVersion(), a.getReviewedBy(), a.getReviewNote(),
                a.getReviewedAt(), h == null ? List.of() : h.reviews(a.getId()));
    }

    private static void eq(StringBuilder where, MapSqlParameterSource p, String column, String param, String value) {
        if (value != null) {
            where.append(" AND ").append(column).append(" = :").append(param);
            p.addValue(param, value);
        }
    }

    private Map<String, Object> map(String text) {
        try {
            return json.readValue(text, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("anomaly.details is not a JSON object", e);
        }
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
