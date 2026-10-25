package com.ielop.platform.assistant;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.ielop.platform.common.exception.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The OpenAI-compatible gateway against a stubbed provider (T24): request shape (path, bearer key,
 * model, structured output), one retry on 429 and 5xx only, timeouts, and the tool-call loop.
 */
class OpenAiCompatibleGatewayTest {

    static final String PATH = "/v1/chat/completions";

    private WireMockServer wire;

    @BeforeEach
    void start() {
        wire = new WireMockServer(wireMockConfig().dynamicPort());
        wire.start();
    }

    @AfterEach
    void stop() {
        wire.stop();
    }

    private OpenAiCompatibleGateway gateway(int timeoutSeconds) {
        AiProperties p = new AiProperties();
        p.setProvider("openai");
        p.setBaseUrl("http://localhost:" + wire.port() + "/v1/");
        p.setApiKey("test-key");
        p.setModel("model-under-test");
        p.setTimeoutSeconds(timeoutSeconds);
        return new OpenAiCompatibleGateway(p);
    }

    static String completion(String content) {
        String escaped = content.replace("\\", "\\\\").replace("\"", "\\\"");
        return """
                {"id":"c1","object":"chat.completion","created":1,"model":"model-under-test",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":120,"completion_tokens":30,"total_tokens":150}}""".formatted(escaped);
    }

    static TraceContext context() {
        return new TraceContext(TraceContext.VERSION,
                new TraceContext.Trace("flow-x", "REQ-20260515-000001", "FAILED", Map.of("requester", "usr-1042"), "usr-1042", null, null,
                        List.of(new TraceContext.Stage("Create", "FAILED", "2026-05-15T10:00:00.000Z", "2026-05-15T10:00:02.000Z", 2000L, "usr-1042")),
                        new TraceContext.FirstFailure("Create", "svc", "c.a.w.Handler", "java.lang.IllegalStateException")),
                List.of(), List.of(new TraceContext.Evidence("ev1", "Create", "svc", "2026-05-15T10:00:01.000Z", "ERROR", "OBSERVED",
                "c.a.w.Handler", null, "Request failed <b>")), new TraceContext.Truncation(0, 0));
    }

    private static AiGateway.SummaryRequest summaryRequest() {
        return new AiGateway.SummaryRequest(context(), "system prompt", "{\"type\":\"object\"}", List.of());
    }

    @Test
    void summarySendsTheModelKeyAndStructuredOutputToChatCompletions() {
        wire.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody(completion("{\"headline\":\"h\"}"))));

        AiGateway.SummaryResult r = gateway(5).summarize(summaryRequest());

        assertThat(r.rawText()).isEqualTo("{\"headline\":\"h\"}");
        assertThat(r.usage()).isEqualTo(new AiGateway.Usage(120, 30));
        assertThat(r.model()).isEqualTo("model-under-test");
        wire.verify(1, postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer test-key"))
                .withRequestBody(matchingJsonPath("$.model", equalTo("model-under-test")))
                .withRequestBody(matchingJsonPath("$.response_format.type", equalTo("json_schema")))
                .withRequestBody(containing("<evidence id=\\\"ev1\\\""))
                .withRequestBody(containing("Request failed &lt;b&gt;")));
    }

    @Test
    void a429IsRetriedOnceThenSucceeds() {
        wire.stubFor(post(urlEqualTo(PATH)).inScenario("quota").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withBody("{\"error\":{\"message\":\"quota\"}}")).willSetStateTo("again"));
        wire.stubFor(post(urlEqualTo(PATH)).inScenario("quota").whenScenarioStateIs("again")
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(completion("ok"))));

        assertThat(gateway(5).summarize(summaryRequest()).rawText()).isEqualTo("ok");
        wire.verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void repeatedServerErrorsBecomeProviderErrorsAfterOneRetry() {
        wire.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503).withBody("busy")));
        assertThatThrownBy(() -> gateway(5).summarize(summaryRequest())).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo("AI_PROVIDER_ERROR");
            assertThat(e.getMessage()).contains("503").doesNotContain("system prompt");
        });
        wire.verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void clientErrorsAndTimeoutsAreNotRetried() {
        wire.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(400).withBody("{\"error\":\"bad\"}")));
        assertThatThrownBy(() -> gateway(5).summarize(summaryRequest())).isInstanceOf(ApiException.class);
        wire.verify(1, postRequestedFor(urlEqualTo(PATH)));

        wire.resetAll();
        wire.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withFixedDelay(2_500).withHeader("Content-Type", "application/json")
                .withBody(completion("late"))));
        assertThatThrownBy(() -> gateway(1).summarize(summaryRequest()))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo("AI_PROVIDER_ERROR"));
        wire.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void toolCallsRunServerSideAndTheFinalAnswerComesBack() {
        wire.stubFor(post(urlEqualTo(PATH)).inScenario("tools").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        {"id":"c1","object":"chat.completion","created":1,"model":"model-under-test",
                         "choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[
                           {"id":"call_1","type":"function","function":{"name":"list_anomalies","arguments":"{\\"ruleId\\":\\"R07\\"}"},
                            "extra_content":{"google":{"thought_signature":"sig-123"}}}]},
                           "finish_reason":"tool_calls"}],
                         "usage":{"prompt_tokens":50,"completion_tokens":10,"total_tokens":60}}"""))
                .willSetStateTo("answered"));
        wire.stubFor(post(urlEqualTo(PATH)).inScenario("tools").whenScenarioStateIs("answered")
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(completion("Two brute-force findings [ev:1]."))));

        AtomicInteger calls = new AtomicInteger();
        AiGateway.Tool tool = new AiGateway.Tool("list_anomalies", "List anomalies", "{\"type\":\"object\",\"properties\":{\"ruleId\":{\"type\":\"string\"}}}",
                args -> {
                    calls.incrementAndGet();
                    assertThat(args).contains("R07");
                    return "[{\"id\":1}]";
                });
        AiGateway.ChatTurnResult r = gateway(5).chat(new AiGateway.ChatRequest("system", List.of(new AiGateway.ChatMessage("user", "earlier")),
                "Any brute force?", null, List.of(tool)));

        assertThat(r.answer()).isEqualTo("Two brute-force findings [ev:1].");
        assertThat(calls).hasValue(1);
        wire.verify(2, postRequestedFor(urlEqualTo(PATH)));
        wire.verify(1, postRequestedFor(urlEqualTo(PATH)).withRequestBody(matchingJsonPath("$.messages[?(@.role == 'tool')]")));
        // The provider's extra content on the tool call (Gemini's thought signature) goes back with the replayed call.
        wire.verify(1, postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.messages[?(@.role == 'assistant')].tool_calls[0].extra_content.google.thought_signature",
                        equalTo("sig-123"))));
        wire.verify(2, postRequestedFor(urlEqualTo(PATH)).withRequestBody(matchingJsonPath("$.tools[0].function.name", equalTo("list_anomalies"))));
    }
}
