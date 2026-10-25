package com.ielop.platform.assistant;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.ielop.platform.support.ElasticsearchIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/** AI-010: a model that keeps calling tools gets five calls; the sixth is refused and the turn still ends with an answer. */
class ToolLimitTest extends ElasticsearchIntegrationTest {

    static final WireMockServer WIRE = new WireMockServer(wireMockConfig().dynamicPort());
    static final String PATH = "/v1/chat/completions";

    static {
        WIRE.start();
    }

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry r) {
        r.add("app.ai.provider", () -> "openai");
        r.add("app.ai.base-url", () -> "http://localhost:" + WIRE.port() + "/v1");
        r.add("app.ai.api-key", () -> "test-key");
        r.add("app.ai.model", () -> "stub-model");
    }

    @AfterAll
    static void stop() {
        WIRE.stop();
    }

    @Autowired
    private ChatService chat;
    @Autowired
    private JdbcTemplate jdbc;

    static String toolCall(int n) {
        return """
                {"id":"c%d","object":"chat.completion","created":1,"model":"stub-model",
                 "choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[
                   {"id":"call_%d","type":"function","function":{"name":"explain_rule","arguments":"{\\"ruleId\\":\\"R0%d\\"}"}}]},
                   "finish_reason":"tool_calls"}],
                 "usage":{"prompt_tokens":40,"completion_tokens":5,"total_tokens":45}}""".formatted(n, n, n);
    }

    @Test
    void theSixthToolCallInOneTurnIsRefused() {
        for (int i = 1; i <= 6; i++) {
            WIRE.stubFor(post(urlEqualTo(PATH)).inScenario("loop").whenScenarioStateIs(i == 1 ? Scenario.STARTED : "call" + i)
                    .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(toolCall(i))).willSetStateTo("call" + (i + 1)));
        }
        WIRE.stubFor(post(urlEqualTo(PATH)).inScenario("loop").whenScenarioStateIs("call7")
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody(OpenAiCompatibleGatewayTest.completion("I explained five rules; the sixth lookup was refused."))));

        String user = "tools." + UUID.randomUUID().toString().substring(0, 8);
        UUID id = chat.create(null, user);
        ChatService.Answer a = chat.message(id, user, "Explain rules R01 to R06 one by one.");

        assertThat(a.answer()).contains("sixth lookup was refused");
        assertThat(a.toolCalls()).hasSize(6);
        assertThat(a.toolCalls().subList(0, 5)).allSatisfy(t -> assertThat(t.refused()).isFalse());
        assertThat(a.toolCalls().get(5).refused()).isTrue();
        WIRE.verify(7, postRequestedFor(urlEqualTo(PATH)));
        assertThat(WIRE.findAll(postRequestedFor(urlEqualTo(PATH))).get(6).getBodyAsString()).contains("Tool call limit of 5 reached");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE actor = ? AND action = 'AI_TOOL_CALL' AND metadata->>'refused' = 'true'",
                Integer.class, user)).isEqualTo(1);
    }
}
