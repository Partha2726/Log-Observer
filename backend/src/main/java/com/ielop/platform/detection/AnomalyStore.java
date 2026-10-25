package com.ielop.platform.detection;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Writes findings idempotently (section 10.1, RULE-021): one row per {@code dedup_key}.
 * Evaluating again refreshes the evidence and details of NEW rows only; a reviewed row
 * (CONFIRMED or DISMISSED) is never touched (section 10.2).
 */
@Component
public class AnomalyStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public AnomalyStore(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Returns true when a new anomaly row was inserted. */
    public boolean upsert(Finding f, Instant detectedAt) {
        String details;
        try {
            details = json.writeValueAsString(f.details());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        Boolean inserted = jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    INSERT INTO anomaly (rule_id, severity, trace_id, user_id, client_ip, detected_at, window_start, window_end,
                                         details, evidence_doc_ids, flow_ref, dedup_key)
                    VALUES (?, ?, ?, ?, CAST(? AS inet), ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                    ON CONFLICT (dedup_key) DO UPDATE
                       SET evidence_doc_ids = EXCLUDED.evidence_doc_ids, details = EXCLUDED.details,
                           window_start = EXCLUDED.window_start, window_end = EXCLUDED.window_end,
                           severity = EXCLUDED.severity, trace_id = COALESCE(EXCLUDED.trace_id, anomaly.trace_id)
                     WHERE anomaly.status = 'NEW'
                    RETURNING (xmax = 0) AS inserted""");
            ps.setString(1, f.ruleId());
            ps.setString(2, f.severity().name());
            ps.setObject(3, f.traceId());
            ps.setString(4, f.userId());
            ps.setString(5, f.clientIp());
            ps.setTimestamp(6, Timestamp.from(detectedAt));
            ps.setTimestamp(7, f.windowStart() == null ? null : Timestamp.from(f.windowStart()));
            ps.setTimestamp(8, f.windowEnd() == null ? null : Timestamp.from(f.windowEnd()));
            ps.setString(9, details);
            ps.setArray(10, con.createArrayOf("text", f.evidenceDocIds().toArray()));
            ps.setString(11, f.flowRef());
            ps.setString(12, f.dedupKey());
            return ps;
        }, rs -> rs.next() ? rs.getBoolean("inserted") : Boolean.FALSE);
        return Boolean.TRUE.equals(inserted);
    }
}
