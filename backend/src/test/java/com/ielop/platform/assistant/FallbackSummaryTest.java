package com.ielop.platform.assistant;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.ielop.platform.support.ElasticsearchIntegrationTest;
import com.ielop.platform.support.EsFixtures;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/** A summary answered by a fallback records that provider and model on its {@code ai_summary} row (Step 0). */
class FallbackSummaryTest extends ElasticsearchIntegrationTest {

    static final WireMockServer WIRE = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        WIRE.start();
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry r) {
        r.add("app.ai.provider", () -> "openai");
        r.add("app.ai.base-url", () -> "http://localhost:" + WIRE.port() + "/primary/v1");
        r.add("app.ai.api-key", () -> "k0");
        r.add("app.ai.model", () -> "primary-model");
        r.add("app.ai.fallback1.base-url", () -> "http://127.0.0.1:" + WIRE.port() + "/fallback1/v1");
        r.add("app.ai.fallback1.api-key", () -> "k1");
        r.add("app.ai.fallback1.model", () -> "fallback-model");
        r.add("app.ai.fallback1.max-context-tokens", () -> "5000");
        r.add("app.ai.fallback2.api-key", () -> "");
    }

    @AfterAll
    static void stop() {
        WIRE.stop();
    }

    @Autowired
    private SummaryService summaries;
    @Autowired
    private AiGateway gateway;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void theRowNamesTheFallbackThatAnswered() {
        assertThat(gateway).isInstanceOf(FallbackAiGateway.class);
        assertThat(gateway.models()).containsExactly("primary-model", "fallback-model");
        WIRE.stubFor(post(urlEqualTo("/primary/v1/chat/completions")).willReturn(aResponse().withStatus(503).withBody("busy")));
        String valid = "{\"headline\":\"Failed at Approve\",\"status\":\"FAILED\",\"whatHappened\":[],\"whereItFailed\":null,"
                + "\"anomalyExplanations\":[],\"likelyCause\":null,\"suggestedChecks\":[],\"uncertainty\":\"Short answer.\"}";
        WIRE.stubFor(post(urlEqualTo("/fallback1/v1/chat/completions")).willReturn(aResponse().withHeader("Content-Type", "application/json")
                .withBody(OpenAiCompatibleGatewayTest.completion(valid))));
        String ref = EsFixtures.demoFlows().stream().filter(f -> f.get("scenario").asText().equals("FAIL_DB_TIMEOUT_APPROVE"))
                .map(f -> f.get("flowRef").asText()).findFirst().orElseThrow();
        jdbc.update("DELETE FROM ai_summary WHERE trace_id IN (SELECT id FROM trace WHERE flow_ref = ?)", ref);

        long id = summaries.request(ref, true, "fallback.user").summaryId();
        while (summaries.runNext("w").isPresent()) {
            // drain
        }

        Map<String, Object> row = jdbc.queryForMap("SELECT status, provider, model FROM ai_summary WHERE id = ?", id);
        assertThat(row).containsEntry("status", "COMPLETED").containsEntry("provider", "127.0.0.1").containsEntry("model", "fallback-model");
        assertThat(jdbc.queryForObject("SELECT metadata->>'model' FROM audit_event WHERE action = 'AI_SUMMARY_COMPLETED' AND target_id = ?",
                String.class, String.valueOf(id))).isEqualTo("fallback-model");
    }
}
