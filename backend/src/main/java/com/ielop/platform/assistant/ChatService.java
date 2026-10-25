package com.ielop.platform.assistant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ielop.platform.common.audit.AuditEventMessage;
import com.ielop.platform.common.audit.AuditPublisher;
import com.ielop.platform.common.exception.ApiException;
import com.ielop.platform.common.filter.RequestIdFilter;
import com.ielop.platform.common.ratelimit.RateLimitProperties;
import com.ielop.platform.common.ratelimit.RateLimiter;
import com.ielop.platform.trace.TraceService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The developer assistant (section 11.6). A conversation belongs to the user who created it and is
 * scoped to a flowRef (its redacted context is preloaded) or global. Each message sends the system
 * prompt {@code chat-1}, the context, the last 10 messages and the read-only tools; the answer's
 * {@code [ev:N]} citations are kept only when the conversation issued that ID, and mapped to log
 * documents. Every message and tool call is audited; tokens count against the user's budget.
 */
@Service
public class ChatService {

    static final int HISTORY = 10;
    static final int MAX_MESSAGE = 2_000;
    static final long TURN_TIMEOUT_SECONDS = 60;
    static final Pattern CITATION = Pattern.compile("\\[ev:(\\d+)]");

    public record Citation(String ref, String docId, String stage, String ts) {
    }

    public record Answer(String answer, List<Citation> citations, List<ToolTurn.ToolCall> toolCalls, AiGateway.Usage usage) {
    }

    public record Message(String role, String content, List<Citation> citations, List<ToolTurn.ToolCall> toolCalls, Instant createdAt) {
    }

    public record Conversation(UUID conversationId, String flowRef, Instant createdAt, List<Message> messages) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final TraceService traces;
    private final TraceContextBuilder contexts;
    private final AssistantTools tools;
    private final AiCalls ai;
    private final AiAvailability availability;
    private final AiProperties props;
    private final Prompts prompts;
    private final AuditPublisher audit;
    private final ObjectMapper json;
    private final RateLimiter limiter;
    private final RateLimitProperties limits;
    private final Set<UUID> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final Clock clock = Clock.systemUTC();

    public ChatService(NamedParameterJdbcTemplate jdbc, TraceService traces, TraceContextBuilder contexts, AssistantTools tools, AiCalls ai,
                       AiAvailability availability, AiProperties props, Prompts prompts, AuditPublisher audit, ObjectMapper json,
                       RateLimiter limiter, RateLimitProperties limits) {
        this.limiter = limiter;
        this.limits = limits;
        this.jdbc = jdbc;
        this.traces = traces;
        this.contexts = contexts;
        this.tools = tools;
        this.ai = ai;
        this.availability = availability;
        this.props = props;
        this.prompts = prompts;
        this.audit = audit;
        this.json = json;
    }

    public UUID create(String flowRef, String user) {
        requireEnabled();
        if (flowRef != null) {
            traces.getOrAssemble(flowRef, false);
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.update("INSERT INTO ai_conversation (id, user_id, flow_ref, created_at) VALUES (:id, :u, :ref, :now)",
                new MapSqlParameterSource("id", id).addValue("u", user).addValue("ref", flowRef).addValue("now", Timestamp.from(now)));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("flowRef", flowRef);
        audit.publish(new AuditEventMessage("AI_CONVERSATION_CREATED", user, "ai_conversation", id.toString(), meta, now,
                RequestIdFilter.getCurrentRequestId(), null));
        return id;
    }

    public Conversation get(UUID id, String user) {
        Map<String, Object> c = owned(id, user);
        List<Message> messages = jdbc.query("""
                SELECT role, content, citations::text AS citations, tool_calls::text AS tool_calls, created_at
                  FROM ai_message WHERE conversation_id = :id ORDER BY created_at, id""", new MapSqlParameterSource("id", id),
                (rs, i) -> new Message(rs.getString("role"), rs.getString("content"), citations(rs.getString("citations")),
                        calls(rs.getString("tool_calls")), rs.getTimestamp("created_at").toInstant()));
        return new Conversation(id, (String) c.get("flow_ref"), ((Timestamp) c.get("created_at")).toInstant(), messages);
    }

    public Answer message(UUID id, String user, String text) {
        Map<String, Object> conversation = owned(id, user);
        if (text == null || text.isBlank() || text.length() > MAX_MESSAGE) {
            throw ApiException.validation(Map.of("message", "is required, 1 to " + MAX_MESSAGE + " characters"));
        }
        requireEnabled();
        limiter.acquire("chat:" + user, limits.assistantMessagesPerMinute(), java.time.Duration.ofMinutes(1), "assistant messages");
        // Section 8.10: one message in flight per conversation.
        if (!inFlight.add(id)) {
            throw new ApiException("RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS, "This conversation is still answering the previous message");
        }
        try {
            return answer(id, user, text, conversation);
        } finally {
            inFlight.remove(id);
        }
    }

    private Answer answer(UUID id, String user, String text, Map<String, Object> conversation) {
        String flowRef = (String) conversation.get("flow_ref");

        // Evidence IDs this conversation has issued: the context's lines, then earlier tool results.
        Map<String, ToolTurn.Issued> issued = new LinkedHashMap<>();
        TraceContext context = null;
        StringBuilder system = new StringBuilder(prompts.assistant());
        if (flowRef != null) {
            TraceContextBuilder.Built built = contexts.build(flowRef);
            context = built.context();
            for (TraceContext.Evidence e : context.evidence()) {
                issued.put(e.id(), new ToolTurn.Issued(built.evidenceDocIds().get(e.id()), e.stage(), e.ts()));
            }
            system.append("\n\nThe conversation is about this trace:\n").append(PromptText.context(context));
        } else {
            system.append("\n\nNo trace is attached to this conversation; use the tools to look things up.");
        }
        List<AiGateway.ChatMessage> history = new ArrayList<>();
        jdbc.query("""
                SELECT role, content, tool_calls::text AS tool_calls FROM ai_message WHERE conversation_id = :id
                 ORDER BY created_at DESC, id DESC LIMIT :n""", new MapSqlParameterSource("id", id).addValue("n", HISTORY), rs -> {
            history.add(new AiGateway.ChatMessage(rs.getString("role"), rs.getString("content")));
        });
        Collections.reverse(history);
        jdbc.query("SELECT tool_calls::text AS t FROM ai_message WHERE conversation_id = :id AND tool_calls IS NOT NULL ORDER BY id",
                new MapSqlParameterSource("id", id), rs -> {
                    issued.putAll(issuedOf(rs.getString("t")));
                });

        ToolTurn turn = new ToolTurn(user, id, props.getMaxToolCallsPerTurn(), issued);
        AiGateway.ChatRequest request = new AiGateway.ChatRequest(system.toString(), history, text, context, tools.forTurn(turn));
        AiGateway.ChatTurnResult result = withTimeout(() -> ai.chat(user, request));

        // Keep citations the conversation issued; drop any other [ev:N] from the text.
        Map<String, ToolTurn.Issued> known = turn.issued();
        Set<String> seen = new LinkedHashSet<>();
        Matcher m = CITATION.matcher(result.answer() == null ? "" : result.answer());
        StringBuilder cleaned = new StringBuilder();
        while (m.find()) {
            String ev = "ev" + m.group(1);
            m.appendReplacement(cleaned, known.containsKey(ev) ? Matcher.quoteReplacement(m.group()) : "");
            if (known.containsKey(ev)) {
                seen.add(ev);
            }
        }
        m.appendTail(cleaned);
        String answer = cleaned.toString().replaceAll("[ \\t]+([.,;:])", "$1").strip();
        List<Citation> citations = seen.stream().map(ev -> {
            ToolTurn.Issued i = known.get(ev);
            return new Citation("ev:" + ev.substring(2), i.docId(), i.stage(), i.ts());
        }).toList();

        Instant now = clock.instant();
        MapSqlParameterSource p = new MapSqlParameterSource("c", id).addValue("now", Timestamp.from(now));
        jdbc.update("INSERT INTO ai_message (conversation_id, role, content, created_at) VALUES (:c, 'user', :t, :now)", p.addValue("t", text));
        jdbc.update("""
                INSERT INTO ai_message (conversation_id, role, content, citations, tool_calls, input_tokens, output_tokens, created_at)
                VALUES (:c, 'assistant', :a, CAST(:cit AS jsonb), CAST(:tools AS jsonb), :in, :out, :now2)""",
                new MapSqlParameterSource("c", id).addValue("a", answer).addValue("cit", write(citations))
                        .addValue("tools", write(toolsJson(turn))).addValue("in", result.usage().inputTokens())
                        .addValue("out", result.usage().outputTokens()).addValue("now2", Timestamp.from(now.plusMillis(1))));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("toolCalls", turn.toolCalls().size());
        meta.put("citations", citations.size());
        meta.put("inputTokens", result.usage().inputTokens());
        meta.put("outputTokens", result.usage().outputTokens());
        meta.put("provider", result.provider());
        meta.put("model", result.model());
        audit.publish(new AuditEventMessage("AI_ASSISTANT_MESSAGE", user, "ai_conversation", id.toString(), meta, now,
                RequestIdFilter.getCurrentRequestId(), null));
        return new Answer(answer, citations, turn.toolCalls(), result.usage());
    }

    private <T> T withTimeout(java.util.function.Supplier<T> call) {
        try {
            return CompletableFuture.supplyAsync(call).get(TURN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new ApiException("AI_TIMEOUT", HttpStatus.GATEWAY_TIMEOUT, "The assistant did not answer within " + TURN_TIMEOUT_SECONDS + " s");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            throw AiException.providerError("The assistant failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw AiException.providerError("Interrupted", e);
        }
    }

    private Map<String, Object> owned(UUID id, String user) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT user_id, flow_ref, created_at FROM ai_conversation WHERE id = :id",
                new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) {
            throw ApiException.notFound("No conversation " + id);
        }
        if (!user.equals(rows.get(0).get("user_id"))) {
            throw new ApiException("FORBIDDEN", HttpStatus.FORBIDDEN, "This conversation belongs to another user");
        }
        return rows.get(0);
    }

    private void requireEnabled() {
        if (!availability.enabled()) {
            throw AiException.disabled(availability.reason());
        }
    }

    private ObjectNode toolsJson(ToolTurn turn) {
        ObjectNode n = json.createObjectNode();
        n.set("calls", json.valueToTree(turn.toolCalls()));
        n.set("issued", json.valueToTree(turn.issuedThisTurn()));
        return n;
    }

    private Map<String, ToolTurn.Issued> issuedOf(String toolsText) {
        Map<String, ToolTurn.Issued> out = new LinkedHashMap<>();
        JsonNode n = read(toolsText);
        if (n != null) {
            n.path("issued").fields().forEachRemaining(e -> out.put(e.getKey(),
                    new ToolTurn.Issued(e.getValue().path("docId").asText(), e.getValue().path("stage").asText(null), e.getValue().path("ts").asText(null))));
        }
        return out;
    }

    private List<Citation> citations(String text) {
        JsonNode n = read(text);
        List<Citation> out = new ArrayList<>();
        if (n != null) {
            n.forEach(c -> out.add(new Citation(c.path("ref").asText(), c.path("docId").asText(), c.path("stage").asText(null), c.path("ts").asText(null))));
        }
        return out;
    }

    private List<ToolTurn.ToolCall> calls(String text) {
        JsonNode n = read(text);
        List<ToolTurn.ToolCall> out = new ArrayList<>();
        if (n != null) {
            n.path("calls").forEach(c -> out.add(new ToolTurn.ToolCall(c.path("name").asText(), c.path("args").asText(), c.path("resultCount").asInt(),
                    c.path("durationMs").asLong(), c.path("refused").asBoolean())));
        }
        return out;
    }

    private JsonNode read(String text) {
        try {
            return text == null ? null : json.readTree(text);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
