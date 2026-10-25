package com.ielop.platform.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ielop.platform.auth.security.AppUserPrincipal;
import com.ielop.platform.auth.security.JwtService;
import com.ielop.platform.support.IntegrationTest;
import com.ielop.platform.support.TestUsers;
import com.ielop.platform.user.RoleName;
import com.ielop.platform.user.RoleRepository;
import com.ielop.platform.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Persistent audit (T23): each audited action writes exactly one row (AUD-001); admin and audit APIs are ADMINISTRATOR-only (SEC-002). */
@AutoConfigureMockMvc
class AuditTest extends IntegrationTest {

    @Autowired
    private MockMvc mvc;
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

    private String adminName;
    private String devopsName;
    private String admin;
    private String devops;

    @BeforeEach
    void setUp() {
        adminName = "audit.admin." + UUID.randomUUID().toString().substring(0, 8);
        devopsName = "audit.devops." + UUID.randomUUID().toString().substring(0, 8);
        admin = jwt.generateToken(new AppUserPrincipal(TestUsers.createUser(users, roles, encoder, adminName, RoleName.ADMINISTRATOR, true)));
        devops = jwt.generateToken(new AppUserPrincipal(TestUsers.createUser(users, roles, encoder, devopsName, RoleName.BACKEND_DEVOPS_ENGINEER, true)));
    }

    private int rows(String action, String actor) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = ? AND actor = ?", Integer.class, action, actor);
    }

    private void login(String username, String password, int expected) throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", username, "password", password)))).andExpect(status().is(expected));
    }

    @Test
    void toolLoginSuccessAndFailureWriteOneRowEachWithoutThePassword() throws Exception {
        login(devopsName, TestUsers.RAW_PASSWORD, 200);
        assertThat(rows("TOOL_LOGIN_SUCCESS", devopsName)).isEqualTo(1);

        login(devopsName, "WrongPassword-123!", 401);
        assertThat(rows("TOOL_LOGIN_FAILURE", devopsName)).isEqualTo(1);
        String metadata = jdbc.queryForObject("SELECT metadata::text FROM audit_event WHERE action = 'TOOL_LOGIN_FAILURE' AND actor = ?",
                String.class, devopsName);
        assertThat(metadata).contains("BadCredentialsException").doesNotContain("WrongPassword");
        assertThat(rows("TOOL_LOGIN_SUCCESS", devopsName)).isEqualTo(1);
    }

    @Test
    void anomalyReviewWritesOneRowAndShowsInTheHistoryAndAFailedReviewWritesNone() throws Exception {
        long id = jdbc.queryForObject("""
                INSERT INTO anomaly (rule_id, severity, detected_at, details, evidence_doc_ids, status, dedup_key)
                VALUES ('R04', 'HIGH', now(), '{}'::jsonb, ARRAY['doc-1'], 'NEW', ?) RETURNING id""", Long.class, "R04:audit:" + UUID.randomUUID());
        String body = json.writeValueAsString(Map.of("status", "CONFIRMED", "note", "Stage order checked", "version", 0));
        mvc.perform(patch("/api/anomalies/" + id).header("Authorization", "Bearer " + devops).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(patch("/api/anomalies/" + id).header("Authorization", "Bearer " + devops).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict());

        List<Map<String, Object>> audit = jdbc.queryForList(
                "SELECT actor, metadata->>'fromStatus' AS f, metadata->>'toStatus' AS t, metadata->>'note' AS n, request_id "
                        + "FROM audit_event WHERE action = 'ANOMALY_REVIEWED' AND target_type = 'anomaly' AND target_id = ?", String.valueOf(id));
        assertThat(audit).singleElement().satisfies(r -> {
            assertThat(r.get("actor")).isEqualTo(devopsName);
            assertThat(r.get("f")).isEqualTo("NEW");
            assertThat(r.get("t")).isEqualTo("CONFIRMED");
            assertThat(r.get("n")).isEqualTo("Stage order checked");
            assertThat(r.get("request_id")).isNotNull();
        });

        JsonNode detail = json.readTree(mvc.perform(get("/api/anomalies/" + id).header("Authorization", "Bearer " + devops))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(detail.get("history")).singleElement().satisfies(h -> {
            assertThat(h.get("actor").asText()).isEqualTo(devopsName);
            assertThat(h.get("toStatus").asText()).isEqualTo("CONFIRMED");
            assertThat(h.get("note").asText()).isEqualTo("Stage order checked");
        });
        assertThat(detail.get("reviewedBy").asText()).isEqualTo(devopsName);
    }

    @Test
    void adminActionsWriteOneRowEach() throws Exception {
        jdbc.update("DELETE FROM processing_jobs WHERE job_type = 'RULE_SCAN' AND status IN ('QUEUED', 'RUNNING')");
        mvc.perform(post("/api/admin/rule-scan?from=2026-07-01T00:00:00Z&to=2026-07-02T00:00:00Z").header("Authorization", "Bearer " + admin))
                .andExpect(status().isAccepted());
        assertThat(rows("RULE_SCAN_REQUESTED", adminName)).isEqualTo(1);
        jdbc.update("DELETE FROM processing_jobs WHERE job_type = 'RULE_SCAN' AND status IN ('QUEUED', 'RUNNING')");

        byte[] file = "{\"userId\":\"usr-1042\",\"roles\":[\"requester\"],\"limitAmount\":null,\"currency\":\"USD\"}\n".getBytes(StandardCharsets.UTF_8);
        mvc.perform(multipart("/api/admin/user-limits/import").file(new MockMultipartFile("file", "users.jsonl", "application/x-ndjson", file))
                .header("Authorization", "Bearer " + admin)).andExpect(status().isOk());
        assertThat(rows("USER_LIMITS_IMPORTED", adminName)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ GET /api/audit and SEC-002

    @Test
    void auditListFiltersForAdministratorsOnly() throws Exception {
        login(adminName, TestUsers.RAW_PASSWORD, 200);
        login(adminName, "nope-Nope-123!", 401);
        JsonNode page = json.readTree(mvc.perform(get("/api/audit?actor=" + adminName).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(page.get("totalElements").asInt()).isEqualTo(2);
        assertThat(page.get("items").get(0).get("action").asText()).isEqualTo("TOOL_LOGIN_FAILURE");
        assertThat(page.get("items").get(0).get("occurredAt").asText()).endsWith("Z");

        JsonNode filtered = json.readTree(mvc.perform(get("/api/audit?actor=" + adminName + "&action=TOOL_LOGIN_SUCCESS&from=2000-01-01T00:00:00Z")
                .header("Authorization", "Bearer " + admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(filtered.get("items")).singleElement().satisfies(i -> assertThat(i.get("targetType").asText()).isEqualTo("user"));
        JsonNode future = json.readTree(mvc.perform(get("/api/audit?actor=" + adminName + "&from=2999-01-01T00:00:00Z")
                .header("Authorization", "Bearer " + admin)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(future.get("items")).isEmpty();

        mvc.perform(get("/api/audit?action=lower-case").header("Authorization", "Bearer " + admin)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/audit?from=2026-07-02T00:00:00Z&to=2026-07-01T00:00:00Z").header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/audit")).andExpect(status().isUnauthorized());
    }

    @Test
    void devopsEngineersGet403OnAdminAndAuditEndpoints() throws Exception {
        mvc.perform(get("/api/audit").header("Authorization", "Bearer " + devops)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/rule-scan").header("Authorization", "Bearer " + devops)).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/admin/user-limits/import").file(new MockMultipartFile("file", "users.jsonl", "application/x-ndjson", new byte[0]))
                .header("Authorization", "Bearer " + devops)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/reassemble").header("Authorization", "Bearer " + devops)).andExpect(status().isForbidden());
    }
}
