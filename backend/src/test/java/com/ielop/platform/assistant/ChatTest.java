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
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The developer assistant with the fake provider (T27: conversations, citations AI-011, ownership SEC-003, audit). */
@AutoConfigureMockMvc
@Import(SummaryPipelineTest.Scripted.class)
class ChatTest extends ElasticsearchIntegrationTest {

    @Autowired
    private MockMvc mvc;
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
    private String owner;
    private String ownerName;
    private String other;

    @BeforeEach
    void setUp() {
        gateway.reset();
        ownerName = "chat." + UUID.randomUUID().toString().substring(0, 8);
        owner = jwt.generateToken(new AppUserPrincipal(TestUsers.createUser(users, roles, encoder, ownerName, RoleName.BACKEND_DEVOPS_ENGINEER, true)));
        other = jwt.generateToken(new AppUserPrincipal(TestUsers.createUser(users, roles, encoder, "chat.other." + UUID.randomUUID(),
                RoleName.ADMINISTRATOR, true)));
    }

    private static String ref(String scenario) {
        return EsFixtures.demoFlows().stream().filter(f -> f.get("scenario").asText().equals(scenario)).map(f -> f.get("flowRef").asText())
                .findFirst().orElseThrow();
    }

    private ResultActions postJson(String token, String path, Object body) throws Exception {
        return mvc.perform(post(path).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private String conversation(String flowRef) throws Exception {
        Object b = flowRef == null ? Map.of() : Map.of("flowRef", flowRef);
        return body(postJson(owner, "/api/assistant/conversations", b).andExpect(status().isCreated())).get("conversationId").asText();
    }

    @Test
    void aTraceConversationAnswersWithCitationsMappedToLogLines() throws Exception {
        String id = conversation(ref("FAIL_DB_TIMEOUT_APPROVE"));
        JsonNode a = body(postJson(owner, "/api/assistant/conversations/" + id + "/messages", Map.of("message", "Why did the approval fail?"))
                .andExpect(status().isOk()));
        assertThat(a.get("answer").asText()).contains("FAILED").contains("Approve").contains("[ev:");
        JsonNode c = a.get("citations").get(0);
        assertThat(c.get("ref").asText()).startsWith("ev:");
        assertThat(c.get("docId").asText()).hasSize(64);
        assertThat(c.get("stage").asText()).isEqualTo("Approve");
        assertThat(a.get("usage").get("outputTokens").asInt()).isPositive();

        JsonNode history = body(mvc.perform(get("/api/assistant/conversations/" + id).header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk()));
        assertThat(history.get("messages")).hasSize(2);
        assertThat(history.get("messages").get(0).get("role").asText()).isEqualTo("user");
        assertThat(history.get("messages").get(1).get("citations")).isNotEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE actor = ? AND action IN ('AI_CONVERSATION_CREATED', 'AI_ASSISTANT_MESSAGE')",
                Integer.class, ownerName)).isEqualTo(2);
    }

    @Test
    void citationsTheConversationDidNotIssueAreDropped() throws Exception {
        String id = conversation(ref("FAIL_DB_TIMEOUT_APPROVE"));
        gateway.chatScript.add("The limit check timed out [ev:1], see also [ev:999].");
        JsonNode a = body(postJson(owner, "/api/assistant/conversations/" + id + "/messages", Map.of("message", "What happened?"))
                .andExpect(status().isOk()));
        assertThat(a.get("answer").asText()).isEqualTo("The limit check timed out [ev:1], see also.");
        assertThat(a.get("citations")).singleElement().satisfies(c -> assertThat(c.get("ref").asText()).isEqualTo("ev:1"));
    }

    @Test
    void aGlobalConversationUsesToolsAndEveryToolCallIsAudited() throws Exception {
        String id = conversation(null);
        JsonNode a = body(postJson(owner, "/api/assistant/conversations/" + id + "/messages", Map.of("message", "Any recent anomalies?"))
                .andExpect(status().isOk()));
        assertThat(a.get("toolCalls")).singleElement().satisfies(t -> {
            assertThat(t.get("name").asText()).isEqualTo("list_anomalies");
            assertThat(t.get("refused").asBoolean()).isFalse();
        });
        assertThat(a.get("answer").asText()).contains("list_anomalies");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE actor = ? AND action = 'AI_TOOL_CALL'", Integer.class, ownerName))
                .isEqualTo(1);
    }

    @Test
    void conversationsAreVisibleToTheirOwnerOnly() throws Exception {
        String id = conversation(ref("NORMAL"));
        mvc.perform(get("/api/assistant/conversations/" + id).header("Authorization", "Bearer " + other)).andExpect(status().isForbidden());
        postJson(other, "/api/assistant/conversations/" + id + "/messages", Map.of("message", "hi")).andExpect(status().isForbidden());
        mvc.perform(get("/api/assistant/conversations/" + UUID.randomUUID()).header("Authorization", "Bearer " + owner))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidInputsAreRejected() throws Exception {
        postJson(owner, "/api/assistant/conversations", Map.of("flowRef", "REQ-20260515-999999")).andExpect(status().isNotFound());
        postJson(owner, "/api/assistant/conversations", Map.of("flowRef", "nope")).andExpect(status().isBadRequest());
        String id = conversation(null);
        postJson(owner, "/api/assistant/conversations/" + id + "/messages", Map.of("message", "")).andExpect(status().isBadRequest());
        postJson(owner, "/api/assistant/conversations/" + id + "/messages", Map.of("message", "x".repeat(2001))).andExpect(status().isBadRequest());
    }
}
