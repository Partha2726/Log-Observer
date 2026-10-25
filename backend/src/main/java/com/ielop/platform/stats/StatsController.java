package com.ielop.platform.stats;

import com.ielop.platform.flowdef.FlowDefinitionRegistry;
import com.ielop.platform.trace.api.ApiParams;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * {@code GET /api/stats/overview} (section 8.8): dashboard counts from Postgres only, over the
 * assembled traces first seen in {@code [from, to]} (default: the last 7 days) and the anomalies
 * whose window overlaps it. Days are calendar days in {@code LOG_TIMEZONE}.
 */
@RestController
@Tag(name = "Stats")
public class StatsController {

    public record RuleCount(String ruleId, String severity, long total, long open) {
    }

    public record ServiceErrors(String service, long errors) {
    }

    public record ComponentFailures(String logger, long count) {
    }

    public record Day(String day, long completed, long failed, long rejected, long incomplete) {
    }

    public record Overview(String from, String to, String flowId, String timezone, long flows, Map<String, Long> byStatus,
                           Map<String, Map<String, Long>> byStage, List<RuleCount> anomaliesByRule, List<ServiceErrors> byService,
                           List<ComponentFailures> topFailingComponents, List<Day> perDay) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final ApiParams params;
    private final FlowDefinitionRegistry flows;
    private final String timezone;

    public StatsController(NamedParameterJdbcTemplate jdbc, ApiParams params, FlowDefinitionRegistry flows,
                           @Value("${app.log-timezone:UTC}") String timezone) {
        this.jdbc = jdbc;
        this.params = params;
        this.flows = flows;
        this.timezone = timezone;
    }

    @Operation(summary = "Dashboard counts: flows by status and stage, anomalies by rule, errors by service, failing components, flows per day")
    @GetMapping("/api/stats/overview")
    @Transactional(readOnly = true)
    public Overview overview(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
                             @RequestParam(required = false) String flowId) {
        ApiParams.Check check = params.check();
        ApiParams.Window w = check.window(from, to);
        String flow = check.oneOf("flowId", flowId, flows.all().stream().map(d -> d.id()).toList());
        check.done();

        MapSqlParameterSource p = new MapSqlParameterSource("from", Timestamp.from(w.from())).addValue("to", Timestamp.from(w.to()))
                .addValue("flow", flow).addValue("tz", timezone);
        String traces = " FROM trace t WHERE t.first_seen BETWEEN :from AND :to AND (CAST(:flow AS text) IS NULL OR t.flow_id = :flow)";

        Map<String, Long> byStatus = new TreeMap<>();
        jdbc.query("SELECT t.overall_status, count(*) AS n" + traces + " GROUP BY 1", p,
                rs -> { byStatus.put(rs.getString(1), rs.getLong(2)); });
        long total = byStatus.values().stream().mapToLong(Long::longValue).sum();

        Map<String, Map<String, Long>> byStage = new LinkedHashMap<>();
        jdbc.query("SELECT s.stage, s.status, count(*), min(s.stage_order) FROM trace_stage s JOIN trace t ON t.id = s.trace_id"
                        + traces.replace(" FROM trace t WHERE", " AND") + " GROUP BY 1, 2 ORDER BY 4, 1, 2", p,
                rs -> { byStage.computeIfAbsent(rs.getString(1), k -> new TreeMap<>()).put(rs.getString(2), rs.getLong(3)); });

        List<RuleCount> rules = jdbc.query("""
                SELECT rule_id, (array_agg(severity ORDER BY CASE severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END))[1],
                       count(*), count(*) FILTER (WHERE status = 'NEW')
                  FROM anomaly a
                 WHERE COALESCE(a.window_end, a.window_start, a.detected_at) >= :from AND COALESCE(a.window_start, a.detected_at) <= :to
                   AND (CAST(:flow AS text) IS NULL OR a.trace_id IS NULL OR EXISTS (SELECT 1 FROM trace t WHERE t.id = a.trace_id AND t.flow_id = :flow))
                 GROUP BY 1 ORDER BY 1""", p, (rs, i) -> new RuleCount(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)));

        List<ServiceErrors> services = jdbc.query("SELECT l.service, count(*) FROM trace_event_link l JOIN trace t ON t.id = l.trace_id"
                        + traces.replace(" FROM trace t WHERE", " AND") + " AND l.is_error AND l.service IS NOT NULL GROUP BY 1 ORDER BY 2 DESC, 1", p,
                (rs, i) -> new ServiceErrors(rs.getString(1), rs.getLong(2)));

        List<ComponentFailures> components = jdbc.query("SELECT t.first_failure_component, count(*)" + traces
                        + " AND t.first_failure_component IS NOT NULL GROUP BY 1 ORDER BY 2 DESC, 1 LIMIT 10", p,
                (rs, i) -> new ComponentFailures(rs.getString(1), rs.getLong(2)));

        List<Day> perDay = new ArrayList<>(jdbc.query("""
                SELECT to_char(t.first_seen AT TIME ZONE :tz, 'YYYY-MM-DD') AS day,
                       count(*) FILTER (WHERE t.overall_status = 'COMPLETED'), count(*) FILTER (WHERE t.overall_status = 'FAILED'),
                       count(*) FILTER (WHERE t.overall_status = 'REJECTED'), count(*) FILTER (WHERE t.overall_status = 'INCOMPLETE')""" + traces
                        + " GROUP BY 1 ORDER BY 1", p,
                (rs, i) -> new Day(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5))));

        return new Overview(w.from().toString(), w.to().toString(), flow, timezone, total, byStatus, byStage, rules, services, components, perDay);
    }
}
