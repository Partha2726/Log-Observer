package com.ielop.platform.assistant;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.ielop.platform.common.exception.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The ordered provider chain (Step 0): one retry per provider, then the next; smaller context for small providers; a short busy error. */
class FallbackAiGatewayTest {

    static final String P1 = "/primary/v1/chat/completions";
    static final String P2 = "/fallback1/v1/chat/completions";
    static final String P3 = "/fallback2/v1/chat/completions";

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

    private OpenAiCompatibleGateway provider(String name, String path, int maxContext, int timeout) {
        return new OpenAiCompatibleGateway(new OpenAiCompatibleGateway.Settings(name, "http://localhost:" + wire.port() + path, "key-" + name,
                "model-" + name, maxContext), 500, timeout);
    }

    private FallbackAiGateway chain(int fallbackContext) {
        return new FallbackAiGateway(List.of(provider("gemini", "/primary/v1", 12_000, 2), provider("groq", "/fallback1/v1", fallbackContext, 2),
                provider("nvidia", "/fallback2/v1", 12_000, 2)), 500);
    }

    static TraceContext bigContext() {
        List<TraceContext.Evidence> ev = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            ev.add(new TraceContext.Evidence("ev" + i, i % 2 == 0 ? "Create" : "Approve", "svc", String.format("2026-05-15T10:00:%02d.000Z", i),
                    i == 7 ? "ERROR" : "INFO", "OBSERVED", "c.a.w.Handler", null, "line " + i + " " + "x".repeat(400)));
        }
        return new TraceContext(TraceContext.VERSION, new TraceContext.Trace("flow-x", "REQ-20260515-000001", "FAILED", Map.of(), null, null, null,
                List.of(new TraceContext.Stage("Approve", "FAILED", null, null, null, null)), null), List.of(), ev, new TraceContext.Truncation(0, 0));
    }

    private static AiGateway.SummaryRequest request() {
        return new AiGateway.SummaryRequest(bigContext(), "system", "{\"type\":\"object\"}", List.of());
    }

    @Test
    void aBusyPrimaryIsRetriedOnceThenTheNextProviderAnswers() {
        wire.stubFor(post(urlEqualTo(P1)).willReturn(aResponse().withStatus(503).withBody("{\"error\":{\"message\":\"high demand\"}}")));
        wire.stubFor(post(urlEqualTo(P2)).willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody(OpenAiCompatibleGatewayTest.completion("{\"headline\":\"from groq\"}"))));

        AiGateway.SummaryResult r = chain(12_000).summarize(request());

        assertThat(r.provider()).isEqualTo("groq");
        assertThat(r.model()).isEqualTo("model-groq");
        assertThat(r.rawText()).contains("from groq");
        wire.verify(2, postRequestedFor(urlEqualTo(P1)));
        wire.verify(1, postRequestedFor(urlEqualTo(P2)));
        wire.verify(0, postRequestedFor(urlEqualTo(P3)));
    }

    @Test
    void timeoutsAndRateLimitsMoveOnAndASmallProviderGetsAShrunkContext() {
        wire.stubFor(post(urlEqualTo(P1)).willReturn(aResponse().withFixedDelay(3_000).withBody("late")));
        wire.stubFor(post(urlEqualTo(P2)).willReturn(aResponse().withStatus(429).withBody("{\"error\":\"rate limit\"}")));
        wire.stubFor(post(urlEqualTo(P3)).willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody(OpenAiCompatibleGatewayTest.completion("ok"))));

        AiGateway.SummaryResult r = chain(5_000).summarize(request());

        assertThat(r.provider()).isEqualTo("nvidia");
        wire.verify(1, postRequestedFor(urlEqualTo(P1)));
        wire.verify(2, postRequestedFor(urlEqualTo(P2)));
        String full = wire.findAll(postRequestedFor(urlEqualTo(P3))).get(0).getBodyAsString();
        String small = wire.findAll(postRequestedFor(urlEqualTo(P2))).get(0).getBodyAsString();
        int fullEvidence = full.split("<evidence id=").length - 1;
        int smallEvidence = small.split("<evidence id=").length - 1;
        assertThat(fullEvidence).isEqualTo(40);
        assertThat(smallEvidence).isLessThan(fullEvidence).isGreaterThan(0);
        assertThat(small).contains("evidence id=\\\"ev7\\\"").as("the ERROR line is kept");
        assertThat(small.length() / 4).isLessThan(5_000);
    }

    @Test
    void whenEveryProviderFailsTheCallerGetsAShortBusyMessage() {
        wire.stubFor(post(urlEqualTo(P1)).willReturn(aResponse().withStatus(503).withBody("{\"error\":{\"message\":\"secret internal detail\"}}")));
        wire.stubFor(post(urlEqualTo(P2)).willReturn(aResponse().withStatus(500).withBody("boom")));
        wire.stubFor(post(urlEqualTo(P3)).willReturn(aResponse().withStatus(400).withBody("{\"error\":\"bad\"}")));

        assertThatThrownBy(() -> chain(12_000).chat(new AiGateway.ChatRequest("s", List.of(), "hi", null, List.of())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("AI_PROVIDER_ERROR");
                    assertThat(e.getMessage()).isEqualTo("AI provider busy; try again in a minute.");
                });
        wire.verify(2, postRequestedFor(urlEqualTo(P1)));
        wire.verify(2, postRequestedFor(urlEqualTo(P2)));
        wire.verify(1, postRequestedFor(urlEqualTo(P3)));
    }

    @Test
    void contextFitKeepsErrorsAndOnlyDropsWhatIsNeeded() {
        TraceContext c = bigContext();
        assertThat(ContextFit.fit(c, 100, 100_000, 500)).isSameAs(c);
        TraceContext small = ContextFit.fit(c, 100, 3_000, 500);
        assertThat(small.evidence()).extracting(TraceContext.Evidence::id).contains("ev7");
        assertThat(small.truncated().evidenceOmitted()).isEqualTo(40 - small.evidence().size());
        assertThat(small.evidence()).extracting(TraceContext.Evidence::ts).isSorted();
    }
}
