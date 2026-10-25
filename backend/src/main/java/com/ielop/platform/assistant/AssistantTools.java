package com.ielop.platform.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ielop.platform.common.audit.AuditEventMessage;
import com.ielop.platform.common.audit.AuditPublisher;
import com.ielop.platform.common.exception.ApiException;
import com.ielop.platform.detection.AnomalyService;
import com.ielop.platform.logsearch.LogCriteria;
import com.ielop.platform.logsearch.LogHit;
import com.ielop.platform.logsearch.LogQueryService;
import com.ielop.platform.trace.api.ActivityController;
import com.ielop.platform.trace.api.ApiDtos;
import com.ielop.platform.trace.api.ApiParams;
import com.ielop.platform.trace.api.SearchController;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The assistant's five read-only tools (section 11.6): {@code search_logs}, {@code get_trace},
 * {@code list_anomalies}, {@code get_user_activity} and {@code explain_rule}. They run on the server,
 * validate and cap their arguments (ranges of at most 7 days, at most 20 results), redact what they
 * return, wrap it in {@code <evidence>} tags (log lines get new {@code evN} IDs), and are audited.
 * After {@code max-tool-calls-per-turn} calls in one turn, further calls are refused.
 */
@Component
@AssistantTool
public class AssistantTools {

    static final int MAX_RESULTS = 20;
    static final Duration MAX_RANGE = Duration.ofDays(7);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final SearchController search;
    private final LogQueryService logs;
    private final TraceContextBuilder contexts;
    private final AnomalyService anomalies;
    private final ActivityController activity;
    private final ApiParams params;
    private final Redactor redactor;
    private final AuditPublisher audit;
    private final ObjectMapper json;

    public AssistantTools(SearchController search, LogQueryService logs, TraceContextBuilder contexts, AnomalyService anomalies,
                          ActivityController activity, ApiParams params, Redactor redactor, AuditPublisher audit, ObjectMapper json) {
        this.search = search;
        this.logs = logs;
        this.contexts = contexts;
        this.anomalies = anomalies;
        this.activity = activity;
        this.params = params;
        this.redactor = redactor;
        this.audit = audit;
        this.json = json;
    }

    public List<AiGateway.Tool> forTurn(ToolTurn turn) {
        return List.of(
                tool(turn, "search_logs", "Search log lines (free text and filters) in a time range of at most 7 days; returns up to 20 redacted lines, each with an evidence ID.",
                        """
                        {"type":"object","properties":{"query":{"type":"string"},"service":{"type":"string"},"stage":{"type":"string"},
                         "level":{"type":"string","enum":["ERROR","WARN","INFO","DEBUG"]},"requestId":{"type":"string"},
                         "from":{"type":"string","description":"ISO-8601 instant"},"to":{"type":"string","description":"ISO-8601 instant"}},
                         "required":["query"]}""", args -> searchLogs(turn, args)),
                tool(turn, "get_trace", "The assembled trace of a flowRef: stages, roles, status, first failure and anomalies (no log lines).",
                        "{\"type\":\"object\",\"properties\":{\"flowRef\":{\"type\":\"string\"}},\"required\":[\"flowRef\"]}", this::getTrace),
                tool(turn, "list_anomalies", "Up to 20 anomalies, newest first, optionally by rule, user and time range.",
                        """
                        {"type":"object","properties":{"ruleId":{"type":"string"},"userId":{"type":"string"},
                         "from":{"type":"string","description":"ISO-8601 instant"},"to":{"type":"string","description":"ISO-8601 instant"}}}""",
                        this::listAnomalies),
                tool(turn, "get_user_activity", "A user's sessions, failed sign-ins, flows and permission denials in a range of at most 7 days.",
                        """
                        {"type":"object","properties":{"userId":{"type":"string"},"from":{"type":"string"},"to":{"type":"string"}},
                         "required":["userId","from","to"]}""", this::userActivity),
                tool(turn, "explain_rule", "The definition of an anomaly rule (R01 to R15).",
                        "{\"type\":\"object\",\"properties\":{\"ruleId\":{\"type\":\"string\"}},\"required\":[\"ruleId\"]}", this::explainRule));
    }

    /** Result of one tool: the text for the model and how many items it holds. */
    record Result(String text, int count) {
    }

    private AiGateway.Tool tool(ToolTurn turn, String name, String description, String schema, Function<JsonNode, Result> body) {
        return new AiGateway.Tool(name, description, schema, rawArgs -> {
            long started = System.nanoTime();
            String args = rawArgs == null ? "{}" : rawArgs;
            String shownArgs = cut(redactor.redact(args), 500);
            if (turn.calls.incrementAndGet() > turn.maxCalls) {
                turn.record(new ToolTurn.ToolCall(name, shownArgs, 0, 0, true));
                audit(turn, name, shownArgs, 0, 0, true);
                return "{\"error\":\"Tool call limit of " + turn.maxCalls + " reached for this message. Answer now with the evidence you have.\"}";
            }
            Result r;
            try {
                r = body.apply(json.readTree(args));
            } catch (ApiException e) {
                r = new Result("{\"error\":" + quote(e.getMessage() + (e.fieldErrors() == null ? "" : " " + e.fieldErrors())) + "}", 0);
            } catch (Exception e) {
                r = new Result("{\"error\":" + quote("The tool failed: " + e.getClass().getSimpleName()) + "}", 0);
            }
            long ms = (System.nanoTime() - started) / 1_000_000;
            turn.record(new ToolTurn.ToolCall(name, shownArgs, r.count(), ms, false));
            audit(turn, name, shownArgs, r.count(), ms, false);
            return r.text();
        });
    }

    private void audit(ToolTurn turn, String name, String args, int count, long ms, boolean refused) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("tool", name);
        meta.put("args", args);
        meta.put("resultCount", count);
        meta.put("durationMs", ms);
        meta.put("refused", refused);
        audit.publish(new AuditEventMessage("AI_TOOL_CALL", turn.user, "ai_conversation", turn.conversationId.toString(), meta,
                Instant.now(), null, null));
    }

    // ------------------------------------------------------------------ tools

    Result searchLogs(ToolTurn turn, JsonNode a) {
        Instant[] range = range(a, true);
        String query = text(a, "query");
        List<LogHit> hits;
        Map<String, String> stages = new LinkedHashMap<>();
        if (text(a, "requestId") != null) {
            LogCriteria.Builder c = LogCriteria.builder().requestId(text(a, "requestId")).between(range[0], range[1]).text(query);
            if (text(a, "service") != null) {
                c.service(text(a, "service"));
            }
            if (text(a, "level") != null) {
                c.levels(List.of(text(a, "level")));
            }
            hits = logs.searchPage("assistant.search", c.build(), 0, MAX_RESULTS, false).hits();
        } else {
            var page = search.logs(query, text(a, "stage"), text(a, "level"), text(a, "service"), null, null,
                    range[0].toString(), range[1].toString(), 0, MAX_RESULTS);
            page.items().forEach(l -> stages.put(l.docId(), l.stage()));
            hits = logs.getByIds("assistant.search", page.items().stream().map(ApiDtos.LogLine::docId).toList());
        }
        StringBuilder out = new StringBuilder();
        for (LogHit h : hits) {
            String stage = stages.get(h.id());
            String ts = h.timestamp() == null ? null : TS.format(h.timestamp());
            String id = turn.issue(h.id(), stage, ts);
            String body = h.messageBody() != null ? h.messageBody() : h.message();
            out.append("<evidence id=\"").append(id).append("\" ts=\"").append(ts).append("\" service=\"").append(h.service())
                    .append("\" level=\"").append(h.level()).append("\" stage=\"").append(stage).append("\"")
                    .append(h.requestId() != null ? " requestId=\"" + h.requestId() + "\"" : "")
                    .append(h.flowRefs().isEmpty() ? "" : " flowRefs=\"" + String.join(",", h.flowRefs()) + "\"")
                    .append(">").append(PromptText.escape(cut(redactor.redact(body), 600))).append("</evidence>\n");
        }
        return new Result(hits.isEmpty() ? "<evidence>No log lines matched.</evidence>" : out.toString(), hits.size());
    }

    Result getTrace(JsonNode a) {
        String ref = text(a, "flowRef");
        ApiParams.Check check = params.check();
        check.flowRef("flowRef", ref, true);
        check.done();
        TraceContext c = contexts.build(ref).context();
        ObjectNode node = json.valueToTree(new TraceContext(c.contextVersion(), c.trace(), c.anomalies(), List.of(), null));
        node.remove("evidence");
        node.remove("truncated");
        return new Result(wrap(node.toString()), 1);
    }

    Result listAnomalies(JsonNode a) {
        Instant[] range = range(a, false);
        String rule = text(a, "ruleId");
        if (rule != null && !rule.matches("^R\\d{2}$")) {
            throw ApiException.validation(Map.of("ruleId", "must look like R01"));
        }
        ApiParams.Check check = params.check();
        String user = check.userId("userId", text(a, "userId"), false);
        check.done();
        var page = anomalies.list(new AnomalyService.Filter(rule, null, null, user, null, null, range[0], range[1]), 0, MAX_RESULTS);
        ArrayNode items = json.valueToTree(page.items());
        return new Result(wrap(items.toString()), page.items().size());
    }

    Result userActivity(JsonNode a) {
        Instant[] range = range(a, true);
        ApiDtos.UserActivity act = activity.activity(text(a, "userId"), range[0].toString(), range[1].toString());
        JsonNode node = json.valueToTree(act);
        int count = act.sessions().size() + act.failedSignIns().size() + act.flows().size() + act.permissionDenials().size();
        return new Result(wrap(node.toString()), count);
    }

    Result explainRule(JsonNode a) {
        String rule = text(a, "ruleId");
        String def = rule == null ? null : RuleCatalogue.RULES.get(rule.toUpperCase());
        if (def == null) {
            throw ApiException.validation(Map.of("ruleId", "must be one of " + RuleCatalogue.RULES.keySet()));
        }
        return new Result(wrap(rule.toUpperCase() + ": " + def), 1);
    }

    // ------------------------------------------------------------------ helpers

    /** [from, to], at most 7 days apart; when {@code bounded}, missing ends default to a 7-day window ending now or at {@code to}. */
    private Instant[] range(JsonNode a, boolean bounded) {
        Instant from = instant(a, "from");
        Instant to = instant(a, "to");
        if (bounded) {
            if (to == null) {
                to = from != null ? from.plus(MAX_RANGE) : Instant.now();
            }
            if (from == null) {
                from = to.minus(MAX_RANGE);
            }
        }
        if (from != null && to != null) {
            if (from.isAfter(to)) {
                throw ApiException.validation(Map.of("from", "must not be after to"));
            }
            if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
                throw ApiException.validation(Map.of("to", "the range may span at most 7 days"));
            }
        }
        return new Instant[]{from, to};
    }

    private static Instant instant(JsonNode a, String field) {
        String v = text(a, field);
        if (v == null) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(v).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.validation(Map.of(field, "must be an ISO-8601 date-time such as 2026-05-15T13:10:02Z"));
        }
    }

    private static String text(JsonNode a, String field) {
        JsonNode v = a == null ? null : a.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }

    private String wrap(String text) {
        return "<evidence>" + PromptText.escape(redactor.redact(text)) + "</evidence>";
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private String quote(String s) {
        try {
            return json.writeValueAsString(s);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "\"error\"";
        }
    }
}
