package com.ielop.platform.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ielop.platform.common.PageResponse;
import com.ielop.platform.trace.api.ApiParams;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** {@code GET /api/audit} (section 8.8): the audit trail, newest first. ADMINISTRATOR only. */
@RestController
@Tag(name = "Administration")
public class AuditController {

    static final Pattern ACTION = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");

    /** One audit row. */
    public record AuditEvent(long id, Instant occurredAt, String actor, String action, String targetType, String targetId,
                             String requestId, Map<String, Object> metadata) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final ApiParams params;
    private final ObjectMapper json;

    public AuditController(NamedParameterJdbcTemplate jdbc, ApiParams params, ObjectMapper json) {
        this.jdbc = jdbc;
        this.params = params;
        this.json = json;
    }

    @Operation(summary = "Audit events, newest first (ADMINISTRATOR)")
    @GetMapping("/api/audit")
    @PreAuthorize("hasRole('ADMINISTRATOR')")
    public PageResponse<AuditEvent> list(@RequestParam(required = false) String actor,
                                         @RequestParam(required = false) String action,
                                         @RequestParam(required = false) String from,
                                         @RequestParam(required = false) String to,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        ApiParams.Check check = params.check();
        String who = check.text("actor", actor, 64);
        String what = check.text("action", action, 64);
        if (what != null && !ACTION.matcher(what).matches()) {
            check.reject("action", "must be an upper-case action name such as ANOMALY_REVIEWED");
        }
        ApiParams.Window w = check.optionalWindow(from, to);
        int p = check.page(page);
        int s = check.size(size);
        check.done();

        StringBuilder where = new StringBuilder(" WHERE TRUE");
        MapSqlParameterSource q = new MapSqlParameterSource();
        if (who != null) {
            where.append(" AND actor = :actor");
            q.addValue("actor", who);
        }
        if (what != null) {
            where.append(" AND action = :action");
            q.addValue("action", what);
        }
        if (w.from() != null) {
            where.append(" AND occurred_at >= :from");
            q.addValue("from", Timestamp.from(w.from()));
        }
        if (w.to() != null) {
            where.append(" AND occurred_at <= :to");
            q.addValue("to", Timestamp.from(w.to()));
        }
        long total = jdbc.queryForObject("SELECT count(*) FROM audit_event" + where, q, Long.class);
        q.addValue("limit", s).addValue("offset", (long) p * s);
        List<AuditEvent> items = jdbc.query("SELECT id, occurred_at, actor, action, target_type, target_id, request_id, metadata::text AS metadata "
                        + "FROM audit_event" + where + " ORDER BY occurred_at DESC, id DESC LIMIT :limit OFFSET :offset", q,
                (rs, i) -> new AuditEvent(rs.getLong("id"), rs.getTimestamp("occurred_at").toInstant(), rs.getString("actor"),
                        rs.getString("action"), rs.getString("target_type"), rs.getString("target_id"), rs.getString("request_id"),
                        map(rs.getString("metadata"))));
        return PageResponse.of(items, p, s, total, false);
    }

    private Map<String, Object> map(String text) {
        if (text == null) {
            return Map.of();
        }
        try {
            return json.readValue(text, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("audit_event.metadata is not a JSON object", e);
        }
    }
}
