package com.ielop.platform.trace.api;

import com.ielop.platform.common.PageResponse;
import com.ielop.platform.common.exception.ApiException;
import com.ielop.platform.flowdef.FlowDefinition;
import com.ielop.platform.flowdef.FlowDefinitionRegistry;
import com.ielop.platform.logsearch.EsUnavailableException;
import com.ielop.platform.logsearch.HitPage;
import com.ielop.platform.logsearch.LogCriteria;
import com.ielop.platform.logsearch.LogFields;
import com.ielop.platform.logsearch.LogHit;
import com.ielop.platform.logsearch.LogQueryService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@code GET /api/search/flows} and {@code GET /api/search/logs} (sections 8.2, 8.8).
 *
 * <p>Flow search reads assembled traces from Postgres first, then adds flowRefs that
 * Elasticsearch knows but that were never assembled ({@code assembled: false}; the
 * search does not assemble them). Stage, status and anomaly filters apply to
 * assembled traces only, since unassembled flows have no status yet.</p>
 */
@RestController
@Tag(name = "Search")
@RequestMapping("/api/search")
public class SearchController {

    static final List<String> OVERALL = List.of("COMPLETED", "FAILED", "REJECTED", "INCOMPLETE");
    static final List<String> STAGE_STATUS = List.of("COMPLETED", "FAILED", "REJECTED", "INCOMPLETE", "NOT_REACHED");
    /** Most unassembled flowRefs one search adds from Elasticsearch. */
    static final int FALLBACK_CAP = 1_000;

    private final NamedParameterJdbcTemplate jdbc;
    private final LogQueryService logs;
    private final FlowDefinitionRegistry flows;
    private final ApiParams params;
    private final LineStages stages;

    public SearchController(NamedParameterJdbcTemplate jdbc, LogQueryService logs, FlowDefinitionRegistry flows, ApiParams params,
                            LineStages stages) {
        this.jdbc = jdbc;
        this.logs = logs;
        this.flows = flows;
        this.params = params;
        this.stages = stages;
    }

    @Operation(summary = "Flow list from assembled traces plus unassembled flowRefs found in Elasticsearch")
    @GetMapping("/flows")
    public PageResponse<ApiDtos.FlowSummary> flows(@RequestParam(required = false) String userId,
                                                   @RequestParam(required = false) String flowRef,
                                                   @RequestParam(required = false) String requestId,
                                                   @RequestParam(required = false) String flowId,
                                                   @RequestParam(required = false) String stage,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) Boolean hasAnomaly,
                                                   @RequestParam(required = false) String from,
                                                   @RequestParam(required = false) String to,
                                                   @RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size) {
        ApiParams.Check check = params.check();
        String user = check.userId("userId", userId, false);
        String ref = check.flowRef("flowRef", flowRef, false);
        String rid = check.requestId("requestId", requestId);
        String flow = check.oneOf("flowId", flowId, flows.all().stream().map(FlowDefinition::id).toList());
        String st = check.text("stage", stage, 40);
        if (st != null && !stages.isKnownStage(st)) {
            check.oneOf("stage", st, List.of());
        }
        String stat = check.oneOf("status", status, st != null ? STAGE_STATUS : OVERALL);
        ApiParams.Window window = check.window(from, to);
        int p = check.page(page);
        int s = check.size(size);
        check.done();

        // Assembled traces (Postgres).
        StringBuilder where = new StringBuilder(" WHERE t.first_seen <= :to AND t.last_seen >= :from");
        MapSqlParameterSource sql = new MapSqlParameterSource()
                .addValue("from", Timestamp.from(window.from())).addValue("to", Timestamp.from(window.to()));
        if (user != null) {
            where.append(" AND EXISTS (SELECT 1 FROM trace_actor a WHERE a.trace_id = t.id AND a.user_id = :user)");
            sql.addValue("user", user);
        }
        if (ref != null) {
            where.append(" AND t.flow_ref = :ref");
            sql.addValue("ref", ref);
        }
        if (rid != null) {
            where.append(" AND EXISTS (SELECT 1 FROM trace_event_link l WHERE l.trace_id = t.id AND l.request_id = :rid)");
            sql.addValue("rid", rid);
        }
        if (flow != null) {
            where.append(" AND t.flow_id = :flow");
            sql.addValue("flow", flow);
        }
        if (st != null) {
            where.append(" AND EXISTS (SELECT 1 FROM trace_stage s WHERE s.trace_id = t.id AND s.stage = :stage AND ")
                    .append(stat != null ? "s.status = :status)" : "s.status <> 'NOT_REACHED')");
            sql.addValue("stage", st);
        } else if (stat != null) {
            where.append(" AND t.overall_status = :status");
        }
        if (stat != null) {
            sql.addValue("status", stat);
        }
        if (hasAnomaly != null) {
            // Dismissed anomalies do not count (section 10.2).
            where.append(hasAnomaly ? " AND " : " AND NOT ")
                    .append("EXISTS (SELECT 1 FROM anomaly an WHERE an.trace_id = t.id AND an.status <> 'DISMISSED')");
        }
        long assembledTotal = jdbc.queryForObject("SELECT count(*) FROM trace t" + where, sql, Long.class);

        // Unassembled flowRefs from Elasticsearch, when no assembled-only filter is set.
        List<String> extra = new ArrayList<>();
        boolean lowerBound = false;
        boolean statusFilters = st != null || stat != null || hasAnomaly != null;
        if (!statusFilters) {
            try {
                LogCriteria.Builder c = LogCriteria.builder().between(window.from(), window.to()).hasFlowRef(true);
                if (user != null) {
                    c.userId(user);
                }
                if (ref != null) {
                    c.flowRef(ref);
                }
                if (rid != null) {
                    c.requestId(rid);
                }
                Map<String, Long> found = logs.terms("search.flows", c.build(), LogFields.FLOW_REFS, FALLBACK_CAP);
                lowerBound = found.size() >= FALLBACK_CAP;
                Set<String> candidates = new TreeSet<>();
                for (String r : found.keySet()) {
                    if ((ref == null || ref.equals(r)) && flows.forFlowRef(r).map(d -> flow == null || d.id().equals(flow)).orElse(false)) {
                        candidates.add(r);
                    }
                }
                if (!candidates.isEmpty()) {
                    Set<String> assembled = new TreeSet<>(jdbc.queryForList("SELECT flow_ref FROM trace WHERE flow_ref IN (:refs)",
                            new MapSqlParameterSource("refs", candidates), String.class));
                    candidates.removeAll(assembled);
                }
                extra.addAll(candidates);
            } catch (EsUnavailableException e) {
                if (assembledTotal == 0) {
                    throw e;
                }
            }
        }

        long offset = (long) p * s;
        List<ApiDtos.FlowSummary> items = new ArrayList<>();
        if (offset < assembledTotal) {
            sql.addValue("limit", s).addValue("offset", offset);
            items.addAll(assembledPage("SELECT t.* FROM trace t" + where + " ORDER BY t.first_seen DESC, t.flow_ref LIMIT :limit OFFSET :offset", sql));
        }
        long extraOffset = Math.max(0, offset - assembledTotal);
        for (int i = (int) extraOffset; i < extra.size() && items.size() < s; i++) {
            String r = extra.get(i);
            items.add(new ApiDtos.FlowSummary(r, flows.forFlowRef(r).map(FlowDefinition::id).orElse(null), null, Map.of(),
                    null, null, null, null, null, null, null, 0, null, false));
        }
        return PageResponse.of(items, p, s, assembledTotal + extra.size(), lowerBound);
    }

    private List<ApiDtos.FlowSummary> assembledPage(String query, MapSqlParameterSource sql) {
        List<Map<String, Object>> rows = jdbc.queryForList(query, sql);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> ids = rows.stream().map(r -> ((Number) r.get("id")).longValue()).toList();
        Map<Long, Map<String, String>> actors = new HashMap<>();
        jdbc.query("SELECT trace_id, role, user_id FROM trace_actor WHERE trace_id IN (:ids) ORDER BY trace_id, role",
                new MapSqlParameterSource("ids", ids), rs -> {
                    actors.computeIfAbsent(rs.getLong("trace_id"), k -> new LinkedHashMap<>()).put(rs.getString("role"), rs.getString("user_id"));
                });
        Map<Long, Integer> anomalyCount = new HashMap<>();
        Map<Long, String> maxSeverity = new HashMap<>();
        jdbc.query("""
                SELECT trace_id, count(*) AS n,
                       (array_agg(severity ORDER BY CASE severity WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END))[1] AS top
                FROM anomaly WHERE trace_id IN (:ids) AND status <> 'DISMISSED' GROUP BY trace_id""",
                new MapSqlParameterSource("ids", ids), rs -> {
                    anomalyCount.put(rs.getLong("trace_id"), rs.getInt("n"));
                    maxSeverity.put(rs.getLong("trace_id"), rs.getString("top"));
                });
        List<ApiDtos.FlowSummary> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            long id = ((Number) r.get("id")).longValue();
            String flowId = (String) r.get("flow_id");
            Map<String, String> byRole = orderedByDefinition(flowId, actors.getOrDefault(id, Map.of()));
            out.add(new ApiDtos.FlowSummary((String) r.get("flow_ref"), flowId, (String) r.get("overall_status"), byRole,
                    (String) r.get("requester_user_id"), (String) r.get("approver_user_id"), (String) r.get("executor_user_id"),
                    ((Timestamp) r.get("first_seen")).toInstant(), ((Timestamp) r.get("last_seen")).toInstant(),
                    (java.math.BigDecimal) r.get("amount"), r.get("currency") == null ? null : r.get("currency").toString().trim(),
                    anomalyCount.getOrDefault(id, 0), maxSeverity.get(id), true));
        }
        return out;
    }

    private Map<String, String> orderedByDefinition(String flowId, Map<String, String> actors) {
        Map<String, String> out = new LinkedHashMap<>();
        flows.byId(flowId).ifPresent(d -> d.roles().forEach(role -> {
            if (actors.containsKey(role)) {
                out.put(role, actors.get(role));
            }
        }));
        actors.forEach(out::putIfAbsent);
        return out;
    }

    @Operation(summary = "Free-text log search with filters (7-day default range, 31 days at most)")
    @GetMapping("/logs")
    public PageResponse<ApiDtos.LogLine> logs(@RequestParam(required = false) String q,
                                              @RequestParam(required = false) String stage,
                                              @RequestParam(required = false) String level,
                                              @RequestParam(required = false) String service,
                                              @RequestParam(required = false) String host,
                                              @RequestParam(required = false) String clientIp,
                                              @RequestParam(required = false) String from,
                                              @RequestParam(required = false) String to,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        ApiParams.Check check = params.check();
        String text = check.text("q", q, 500);
        String st = check.text("stage", stage, 40);
        if (st != null && !stages.isKnownStage(st)) {
            check.oneOf("stage", st, List.of());
        }
        String lvl = check.oneOf("level", level == null ? null : level.toUpperCase(), List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL"));
        String svc = check.text("service", service, 64);
        String hst = check.text("host", host, 255);
        String ip = check.text("clientIp", clientIp, 45);
        if (ip != null && !ip.matches("^[0-9A-Fa-f:.]+$")) {
            check.oneOf("clientIp", ip, List.of());
        }
        ApiParams.Window window = check.window(from, to);
        int p = check.page(page);
        int s = check.size(size);
        check.done();

        LogCriteria.Builder c = LogCriteria.builder().between(window.from(), window.to()).text(text);
        if (st != null) {
            c.anyOfMatches(stages.conditions(st));
        }
        if (lvl != null) {
            c.levels(List.of(lvl));
        }
        if (svc != null) {
            c.service(svc);
        }
        if (hst != null) {
            c.host(hst);
        }
        if (ip != null) {
            c.clientIp(ip);
        }
        HitPage result = logs.searchPage("search.logs", c.build(), p, s, true);
        Map<String, String> stageOf = stages.stagesOf(result.hits());
        List<ApiDtos.LogLine> items = result.hits().stream()
                .map(h -> line(h, stageOf.get(h.id()), result.highlights().get(h.id()))).toList();
        return PageResponse.of(items, p, s, result.total(), result.totalIsLowerBound());
    }

    static ApiDtos.LogLine line(LogHit h, String stage, String highlight) {
        String text = h.messageBody() != null ? h.messageBody() : h.message();
        String snippet = text == null ? null : (text.length() > 300 ? text.substring(0, 300) + "…" : text);
        return new ApiDtos.LogLine(h.id(), h.timestamp(), h.service(), h.host(), stage, TraceController.sourceFile(h.filePath()),
                h.level(), h.logger(), h.thread(), h.requestId(), h.flowRefs(), h.userId(), h.eventAction(), h.httpStatus(),
                snippet, highlight);
    }

    static ApiException badRequest(String field, String message) {
        return ApiException.validation(Map.of(field, message));
    }
}
