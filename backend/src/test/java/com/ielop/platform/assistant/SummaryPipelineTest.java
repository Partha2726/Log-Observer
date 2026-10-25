package com.ielop.platform.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ielop.platform.auth.security.AppUserPrincipal;
import com.ielop.platform.auth.security.JwtService;
import com.ielop.platform.support.ElasticsearchIntegrationTest;
import com.ielop.platform.support.EsFixtures;
import com.ielop.platform.support.TestUsers;
import com.ielop.platform.user.RoleName;
import com.ielop.platform.user.RoleRepository;
import com.ielop.platform.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The summary pipeline end to end with the fake provider (T26: AI-001, AI-002, AI-014; audit and jobs). */
@AutoConfigureMockMvc
@Import(SummaryPipelineTest.Scripted.class)
class SummaryPipelineTest extends ElasticsearchIntegrationTest {

    @TestConfiguration
    static class Scripted {
        @Bean
        @Primary
        ScriptedAiGateway scriptedAiGateway(ObjectMapper json) {
            return new ScriptedAiGateway(json);
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private SummaryService summaries;
    @Autowired
    private ScriptedAiGateway gateway;
    @Autowired
    private JwtService jwt;
    @Autowired
    private UserRepository users;
    @Autowired
    private RoleRepository roles;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();
    private String token;
    private String username;

    @BeforeEach
    void setUp() {
        gateway.reset();
        username = "summary." + UUID.randomUUID().toString().substring(0, 8);
        token = jwt.generateToken(new AppUserPrincipal(TestUsers.createUser(users, roles, encoder, username, RoleName.BACKEND_DEVOPS_ENGINEER, true)));
    }

    private static String ref(String scenario) {
        return EsFixtures.demoFlows().stream().filter(f -> f.get("scenario").asText().equals(scenario)).map(f -> f.get("flowRef").asText())
                .findFirst().orElseThrow();
    }

    private ResultActions request(String ref, boolean regenerate) throws Exception {
        return mvc.perform(post("/api/traces/" + ref + "/ai-summary").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"regenerate\":" + regenerate + "}"));
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private JsonNode latest(String ref) throws Exception {
        return body(mvc.perform(get("/api/traces/" + ref + "/ai-summary").header("Authorization", "Bearer " + token)).andExpect(status().isOk()));
    }

    private void drain() {
        while (summaries.runNext("test-worker").isPresent()) {
            // run every queued summary
        }
    }

    @Test
    void aFakeSummaryIsValidatedStoredCachedAndRegenerated() throws Exception {
        String ref = ref("FAIL_DB_TIMEOUT_APPROVE");
        jdbc.update("DELETE FROM ai_summary WHERE trace_id IN (SELECT id FROM trace WHERE flow_ref = ?)", ref);

        JsonNode queued = body(request(ref, false).andExpect(status().isAccepted()));
        String jobId = queued.get("jobId").asText();
        JsonNode again = body(request(ref, false).andExpect(status().isAccepted()));
        assertThat(again.get("jobId").asText()).as("the running job is returned, not a new one").isEqualTo(jobId);
        assertThat(latest(ref).get("status").asText()).isEqualTo("REQUESTED");

        drain();
        JsonNode job = body(mvc.perform(get("/api/jobs/" + jobId).header("Authorization", "Bearer " + token)).andExpect(status().isOk()));
        assertThat(job.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(job.get("type").asText()).isEqualTo("AI_SUMMARY");

        JsonNode s = latest(ref);
        assertThat(s.get("status").asText()).isEqualTo("COMPLETED");
        assertThat(s.get("output").get("status").asText()).isEqualTo("FAILED");
        assertThat(s.get("promptVersion").asText()).isEqualTo("sum-1");
        assertThat(s.get("model").asText()).isEqualTo(FakeAiGateway.MODEL);
        assertThat(s.get("contextHash").asText()).hasSize(64);
        String cited = s.get("output").get("whereItFailed").get("evidenceIds").get(0).asText();
        assertThat(s.get("evidence").get(cited).asText()).as("evN maps to a document ID").hasSize(64);
        assertThat(gateway.requests).hasSize(1);

        // AI-014: the same context, prompt and model is served from the cache; Regenerate makes a new row.
        JsonNode cached = body(request(ref, false).andExpect(status().isOk()));
        assertThat(cached.get("summaryId").asLong()).isEqualTo(s.get("summaryId").asLong());
        JsonNode regenerated = body(request(ref, true).andExpect(status().isAccepted()));
        assertThat(regenerated.get("summaryId").asLong()).isNotEqualTo(s.get("summaryId").asLong());
        drain();
        assertThat(latest(ref).get("summaryId").asLong()).isEqualTo(regenerated.get("summaryId").asLong());
        assertThat(gateway.requests).hasSize(2);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE actor = ? AND action = 'AI_SUMMARY_REQUESTED'", Integer.class, username))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE actor = ? AND action = 'AI_SUMMARY_COMPLETED'", Integer.class, username))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT tokens FROM ai_token_usage WHERE user_id = ?", Long.class, username)).isPositive();
    }

    @Test
    void malformedOutputIsRetriedOnceWithTheErrorsThenFails() throws Exception {
        String ref = ref("APPROVER_REJECT");
        jdbc.update("DELETE FROM ai_summary WHERE trace_id IN (SELECT id FROM trace WHERE flow_ref = ?)", ref);
        gateway.script.add("Sure! Here is the summary: {not json");
        gateway.script.add("{\"headline\": \"still wrong\", \"status\": \"COMPLETED\", \"account\": \"999912345678\"}");

        request(ref, false).andExpect(status().isAccepted());
        drain();

        JsonNode s = latest(ref);
        assertThat(s.get("status").asText()).isEqualTo("FAILED");
        assertThat(s.get("errorCode").asText()).isEqualTo("AI_OUTPUT_INVALID");
        assertThat(s.get("output").isNull()).isTrue();
        assertThat(gateway.requests).hasSize(2);
        assertThat(gateway.requests.get(0).validationErrors()).isEmpty();
        assertThat(gateway.requests.get(1).validationErrors()).anyMatch(e -> e.contains("not valid JSON"));
        String raw = jdbc.queryForObject("SELECT raw_output FROM ai_summary WHERE id = ?", String.class, s.get("summaryId").asLong());
        assertThat(raw).contains("still wrong").doesNotContain("999912345678").contains("ACC-****5678");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE actor = ? AND action = 'AI_SUMMARY_FAILED'", Integer.class, username))
                .isEqualTo(1);
    }

    @Test
    void aValidRetryCompletes() throws Exception {
        String ref = ref("SESSION_TIMEOUT");
        jdbc.update("DELETE FROM ai_summary WHERE trace_id IN (SELECT id FROM trace WHERE flow_ref = ?)", ref);
        gateway.script.add("{\"headline\":\"wrong status\"}");
        request(ref, false).andExpect(status().isAccepted());
        drain();
        assertThat(latest(ref).get("status").asText()).isEqualTo("COMPLETED");
        assertThat(gateway.requests).hasSize(2);
    }

    @Test
    void unknownTracesAndBadReferencesAreRejected() throws Exception {
        request("REQ-20260515-999999", false).andExpect(status().isNotFound());
        request("not-a-ref", false).andExpect(status().isBadRequest());
        mvc.perform(get("/api/traces/" + ref("NORMAL") + "/ai-summary").header("Authorization", "Bearer " + token))
                .andExpect(status().is4xxClientError());
        mvc.perform(get("/api/jobs/" + UUID.randomUUID()).header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
    }
}
