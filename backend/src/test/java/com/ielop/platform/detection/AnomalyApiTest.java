package com.ielop.platform.detection;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Anomaly list, detail and review (T22: API-011, API-012, every 10.2 transition). Rows are inserted directly. */
@AutoConfigureMockMvc
class AnomalyApiTest extends IntegrationTest {

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

    private String devops;
    private String admin;
    private String tag;

    @BeforeEach
    void setUp() {
        devops = jwt.generateToken(new AppUserPrincipal(TestUsers.createUser(users, roles, encoder,
                "anomaly.devops." + UUID.randomUUID(), RoleName.BACKEND_DEVOPS_ENGINEER, true)));
        admin = jwt.generateToken(new AppUserPrincipal(TestUsers.createUser(users, roles, encoder,
                "anomaly.admin." + UUID.randomUUID(), RoleName.ADMINISTRATOR, true)));
        tag = UUID.randomUUID().toString().substring(0, 8);
    }

    private long insert(String rule, String severity, String status, String user, String flowRef, String ip, String start, String end) {
        return jdbc.queryForObject("""
                INSERT INTO anomaly (rule_id, severity, user_id, client_ip, detected_at, window_start, window_end, details,
                                     evidence_doc_ids, status, dedup_key, flow_ref)
                VALUES (?, ?, ?, CAST(? AS inet), now(), ?::timestamptz, ?::timestamptz, '{"k": 1}'::jsonb, ARRAY['doc-a', 'doc-b'],
                        ?, ?, ?) RETURNING id""", Long.class,
                rule, severity, user, ip, start, end, status, rule + ":" + tag + ":" + UUID.randomUUID(), flowRef);
    }

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private ResultActions getAs(String token, String path) throws Exception {
        return mvc.perform(get(path).header("Authorization", "Bearer " + token));
    }

    private ResultActions review(String token, long id, String status, String note, Integer version) throws Exception {
        Map<String, Object> b = new java.util.LinkedHashMap<>();
        b.put("status", status);
        b.put("note", note);
        b.put("version", version);
        return mvc.perform(patch("/api/anomalies/" + id).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(b)));
    }

    private static List<Long> ids(JsonNode page) {
        List<Long> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("id").asLong()));
        return out;
    }

    // ------------------------------------------------------------------ API-011

    @Test
    void listFiltersByRuleSeverityStatusUserFlowRefIpAndRange() throws Exception {
        String user = "usr-api-" + tag;
        long a = insert("R07", "HIGH", "NEW", user, null, "203.0.113.70", "2026-07-01T10:00:00Z", "2026-07-01T10:05:00Z");
        long b = insert("R05", "MEDIUM", "CONFIRMED", null, "REQ-20260701-000001", null, "2026-07-01T11:00:00Z", "2026-07-01T11:01:00Z");
        long c = insert("R15", "MEDIUM", "DISMISSED", null, null, "203.0.113.71", "2026-07-02T03:00:00Z", "2026-07-02T03:04:00Z");
        long d = insert("R07", "HIGH", "NEW", user, null, "203.0.113.70", "2026-07-03T09:00:00Z", "2026-07-03T09:08:00Z");
        String range = "&from=2026-07-01T00:00:00Z&to=2026-07-03T23:59:59Z";

        JsonNode byUser = body(getAs(devops, "/api/anomalies?userId=" + user + range).andExpect(status().isOk()));
        assertThat(ids(byUser)).containsExactly(d, a);
        JsonNode first = byUser.get("items").get(1);
        assertThat(first.get("ruleName").asText()).isEqualTo("Sign-in brute force");
        assertThat(first.get("clientIp").asText()).isEqualTo("203.0.113.70");
        assertThat(first.get("evidenceCount").asInt()).isEqualTo(2);
        assertThat(first.get("version").asInt()).isZero();
        assertThat(first.get("windowStart").asText()).isEqualTo("2026-07-01T10:00:00.000Z");

        assertThat(ids(body(getAs(devops, "/api/anomalies?ruleId=R07&userId=" + user + "&from=2026-07-01T10:04:00Z&to=2026-07-01T12:00:00Z"))))
                .as("a window overlapping the range counts").containsExactly(a);
        assertThat(ids(body(getAs(devops, "/api/anomalies?flowRef=REQ-20260701-000001" + range)))).containsExactly(b);
        assertThat(ids(body(getAs(devops, "/api/anomalies?clientIp=203.0.113.71" + range)))).containsExactly(c);
        assertThat(ids(body(getAs(devops, "/api/anomalies?status=DISMISSED&clientIp=203.0.113.71" + range)))).containsExactly(c);
        assertThat(ids(body(getAs(devops, "/api/anomalies?status=NEW&clientIp=203.0.113.71" + range)))).isEmpty();
        assertThat(ids(body(getAs(devops, "/api/anomalies?severity=MEDIUM&flowRef=REQ-20260701-000001" + range)))).containsExactly(b);
        assertThat(ids(body(getAs(devops, "/api/anomalies?severity=HIGH&flowRef=REQ-20260701-000001" + range)))).isEmpty();
        JsonNode paged = body(getAs(devops, "/api/anomalies?userId=" + user + "&size=1&page=1" + range));
        assertThat(ids(paged)).containsExactly(a);
        assertThat(paged.get("totalElements").asInt()).isEqualTo(2);
        assertThat(paged.get("totalPages").asInt()).isEqualTo(2);

        getAs(devops, "/api/anomalies?severity=URGENT").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.severity").exists());
        getAs(devops, "/api/anomalies?ruleId=X1").andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.ruleId").exists());
        getAs(devops, "/api/anomalies?clientIp=not-an-ip").andExpect(status().isBadRequest());
        getAs(devops, "/api/anomalies?from=2026-07-03T00:00:00Z&to=2026-07-01T00:00:00Z").andExpect(status().isBadRequest());
        getAs(devops, "/api/anomalies?size=101").andExpect(status().isBadRequest());
        mvc.perform(get("/api/anomalies")).andExpect(status().isUnauthorized());
    }

    @Test
    void detailCarriesEvidenceAndReviewFields() throws Exception {
        long id = insert("R01", "CRITICAL", "NEW", "usr-1042", "REQ-20260701-000002", null, "2026-07-01T09:00:00Z", "2026-07-01T09:10:00Z");
        JsonNode d = body(getAs(devops, "/api/anomalies/" + id).andExpect(status().isOk()));
        assertThat(d.get("ruleName").asText()).isEqualTo("Self-approval");
        assertThat(d.get("evidenceDocIds")).hasSize(2);
        assertThat(d.get("details").get("k").asInt()).isEqualTo(1);
        assertThat(d.get("history").isArray()).isTrue();
        assertThat(d.has("reviewedBy")).isTrue();
        getAs(devops, "/api/anomalies/999999999").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ------------------------------------------------------------------ 10.2 transitions and API-012

    @Test
    void reviewFollowsTheStateMachine() throws Exception {
        long id = insert("R10", "HIGH", "NEW", "usr-2001", "REQ-20260701-000003", null, "2026-07-01T09:00:00Z", "2026-07-01T09:10:00Z");

        review(devops, id, "NEW", "still new", 0).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        JsonNode confirmed = body(review(devops, id, "CONFIRMED", "Checked with the approver's manager", 0).andExpect(status().isOk()));
        assertThat(confirmed.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.get("version").asInt()).isEqualTo(1);
        assertThat(confirmed.get("reviewNote").asText()).isEqualTo("Checked with the approver's manager");
        assertThat(confirmed.get("reviewedBy").asText()).startsWith("anomaly.devops.");
        assertThat(confirmed.get("reviewedAt").isNull()).isFalse();

        review(devops, id, "DISMISSED", "changed my mind", 1).andExpect(status().isConflict());
        review(devops, id, "NEW", "reopen", 1).andExpect(status().isForbidden());
        JsonNode reopened = body(review(admin, id, "NEW", "Reopened for a second look", 1).andExpect(status().isOk()));
        assertThat(reopened.get("status").asText()).isEqualTo("NEW");
        assertThat(reopened.get("version").asInt()).isEqualTo(2);

        JsonNode dismissed = body(review(devops, id, "DISMISSED", "Approved under a temporary limit", 2).andExpect(status().isOk()));
        assertThat(dismissed.get("status").asText()).isEqualTo("DISMISSED");
        review(devops, id, "CONFIRMED", "no", 3).andExpect(status().isConflict());
        review(admin, id, "NEW", "Reopened again", 3).andExpect(status().isOk());
    }

    @Test
    void staleVersionAndInvalidBodiesAreRejected() throws Exception {
        long id = insert("R12", "LOW", "NEW", "usr-3001", "REQ-20260701-000004", null, "2026-07-01T01:00:00Z", "2026-07-01T01:05:00Z");
        review(devops, id, "CONFIRMED", "first", 0).andExpect(status().isOk());
        review(devops, id, "DISMISSED", "stale", 0).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(
                org.hamcrest.Matchers.containsString("updated by someone else")));

        long other = insert("R12", "LOW", "NEW", "usr-3001", "REQ-20260701-000005", null, "2026-07-01T01:00:00Z", "2026-07-01T01:05:00Z");
        review(devops, other, "CONFIRMED", "", 0).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.note").exists());
        review(devops, other, "CONFIRMED", "x".repeat(1001), 0).andExpect(status().isBadRequest());
        review(devops, other, "CONFIRMED", "ok", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.version").exists());
        review(devops, other, "ESCALATED", "ok", 0).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.status").exists());
        review(devops, 999999999L, "CONFIRMED", "ok", 0).andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT status FROM anomaly WHERE id = ?", String.class, other)).isEqualTo("NEW");
    }

    @Test
    void twoConcurrentReviewsOfOneVersionLetExactlyOneWin() throws Exception {
        long id = insert("R03", "CRITICAL", "NEW", "usr-3001", "REQ-20260701-000006", null, "2026-07-01T09:00:00Z", "2026-07-01T09:10:00Z");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Callable<Integer>> calls = List.of(
                    () -> review(devops, id, "CONFIRMED", "confirm", 0).andReturn().getResponse().getStatus(),
                    () -> review(devops, id, "DISMISSED", "dismiss", 0).andReturn().getResponse().getStatus());
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(calls)) {
                codes.add(f.get());
            }
            assertThat(codes).containsExactlyInAnyOrder(200, 409);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT version FROM anomaly WHERE id = ?", Integer.class, id)).isEqualTo(1);
    }
}
