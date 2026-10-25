package com.ielop.platform.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T24 live check, run only by {@code make ai-live-check} (tag {@code live}): one structured-output call
 * and one tool call against {@code AI_BASE_URL}/{@code AI_MODEL} with {@code AI_API_KEY}. Only generated
 * data is sent (the fictional Acme Cloud trace below). Prints model, tokens and latency, never the prompt.
 */
@Tag("live")
class LiveProviderCheckTest {

    static final String SCHEMA = """
            {"type":"object","required":["headline","status","evidenceIds"],
             "properties":{"headline":{"type":"string"},"status":{"type":"string","enum":["COMPLETED","FAILED","REJECTED","INCOMPLETE"]},
                           "evidenceIds":{"type":"array","items":{"type":"string"}}}}""";

    /** {@code LIVE_PROVIDER}: {@code primary} (default, {@code AI_*}), {@code fallback1} or {@code fallback2} ({@code AI_FALLBACKn_*}). */
    private static OpenAiCompatibleGateway gateway() {
        String which = System.getenv().getOrDefault("LIVE_PROVIDER", "primary");
        String prefix = "primary".equals(which) ? "AI_" : "AI_" + which.toUpperCase() + "_";
        AiProperties p = new AiProperties();
        p.setProvider("openai");
        p.setBaseUrl(System.getenv(prefix + "BASE_URL"));
        p.setApiKey(System.getenv(prefix + "API_KEY"));
        p.setModel(System.getenv().getOrDefault("LIVE_MODEL", System.getenv(prefix + "MODEL")));
        String limit = System.getenv(prefix + "MAX_CONTEXT_TOKENS");
        if (limit != null && !limit.isBlank()) {
            p.setMaxContextTokens(Integer.parseInt(limit.trim()));
        }
        AiAvailability a = AiConfig.availability(p);
        assertThat(a.enabled()).as(which + " settings: " + a.reason()).isTrue();
        System.out.printf("LIVE provider %s (%s)%n", which, OpenAiCompatibleGateway.providerName(p.getBaseUrl()));
        return new OpenAiCompatibleGateway(p);
    }

    @Test
    void structuredOutputAndOneToolCall() throws Exception {
        OpenAiCompatibleGateway g = gateway();
        TraceContext ctx = OpenAiCompatibleGatewayTest.context();

        long t0 = System.nanoTime();
        AiGateway.SummaryResult s = g.summarize(new AiGateway.SummaryRequest(ctx,
                "You summarise a failed flow. Use only the evidence. Answer with JSON matching the schema; cite evidence IDs.", SCHEMA, List.of()));
        JsonNode out = new ObjectMapper().readTree(s.rawText());
        System.out.printf("LIVE structured output: model=%s status=%s evidenceIds=%s tokens=%d+%d latency=%d ms%n", s.model(),
                out.path("status").asText(), out.path("evidenceIds"), s.usage().inputTokens(), s.usage().outputTokens(), (System.nanoTime() - t0) / 1_000_000);
        assertThat(out.path("status").asText()).isEqualTo("FAILED");
        assertThat(out.path("headline").asText()).isNotBlank();

        AtomicInteger calls = new AtomicInteger();
        AiGateway.Tool tool = new AiGateway.Tool("get_trace", "Returns the trace summary of a flowRef",
                "{\"type\":\"object\",\"properties\":{\"flowRef\":{\"type\":\"string\"}},\"required\":[\"flowRef\"]}",
                args -> {
                    calls.incrementAndGet();
                    return "{\"flowRef\":\"REQ-20260515-000001\",\"overallStatus\":\"FAILED\",\"firstFailure\":{\"stage\":\"Create\"}}";
                });
        long t1 = System.nanoTime();
        AiGateway.ChatTurnResult c = g.chat(new AiGateway.ChatRequest(
                "You are a debugging assistant. Call get_trace to look up a flow before answering.", List.of(),
                "Which stage of REQ-20260515-000001 failed? Use the tool.", null, List.of(tool)));
        System.out.printf("LIVE tool call: model=%s toolCalls=%d answerChars=%d tokens=%d+%d latency=%d ms%n", c.model(), calls.get(),
                c.answer().length(), c.usage().inputTokens(), c.usage().outputTokens(), (System.nanoTime() - t1) / 1_000_000);
        assertThat(calls.get()).isGreaterThanOrEqualTo(1);
        assertThat(c.answer()).containsIgnoringCase("create");
    }
}
