package com.ielop.platform.detection;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ielop.platform.auth.security.AppUserPrincipal;
import com.ielop.platform.auth.security.JwtService;
import com.ielop.platform.support.ElasticsearchIntegrationTest;
import com.ielop.platform.support.EsFixtures;
import com.ielop.platform.support.TestUsers;
import com.ielop.platform.trace.StoredTrace;
import com.ielop.platform.trace.TraceService;
import com.ielop.platform.user.RoleName;
import com.ielop.platform.user.RoleRepository;
import com.ielop.platform.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Trace rules run after every assembly against the demo fixture (T20): approval limits
 * are imported by an administrator, findings land in {@code anomaly}, and every finding
 * cites evidence that belongs to its own trace (RULE-022).
 */
@AutoConfigureMockMvc
class RuleEngineIntegrationTest extends ElasticsearchIntegrationTest {

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
    private TraceService traces;
    @Autowired
    private JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    private String token(RoleName role) {
        return jwt.generateToken(new AppUserPrincipal(TestUsers.createUser(users, roles, encoder,
                "rules." + role.name().toLowerCase() + "." + UUID.randomUUID(), role, true)));
    }

    private static JsonNode flow(String scenario) {
        return EsFixtures.demoFlows().stream().filter(f -> f.get("scenario").asText().equals(scenario)).findFirst().orElseThrow();
    }

    @Test
    void rulesRunAfterAssemblyAndEveryFindingCitesItsOwnTracesEvidence() throws Exception {
        // An approval limit below the amount of a completed flow makes R10 fire for it.
        JsonNode normal = flow("NORMAL");
        String approver = normal.get("approver").asText();
        String limits = """
                {"userId":"%s","roles":["approver"],"limitAmount":1.00,"currency":"USD"}
                {"userId":"usr-1042","roles":["requester"],"limitAmount":null,"currency":"USD"}
                """.formatted(approver);
        MockMultipartFile file = new MockMultipartFile("file", "users.jsonl", "application/x-ndjson", limits.getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/admin/user-limits/import").file(file).header("Authorization", "Bearer " + token(RoleName.BACKEND_DEVOPS_ENGINEER)))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/api/admin/user-limits/import").file(file).header("Authorization", "Bearer " + token(RoleName.ADMINISTRATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.skipped").value(1));

        for (JsonNode f : EsFixtures.demoFlows()) {
            traces.getOrAssemble(f.get("flowRef").asText(), true);
        }

        String normalRef = normal.get("flowRef").asText();
        assertThat(ruleIds(normalRef)).contains("R10");
        assertThat(ruleIds(flow("FAIL_DB_TIMEOUT_APPROVE").get("flowRef").asText())).contains("R05");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM anomaly WHERE rule_id = 'R02'", Integer.class))
                .as("R02 is disabled").isZero();

        // RULE-022: every evidence document of a trace-scoped finding is linked to that trace.
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT id, rule_id, trace_id, evidence_doc_ids FROM anomaly WHERE trace_id IS NOT NULL");
        assertThat(rows).isNotEmpty();
        for (Map<String, Object> r : rows) {
            Set<String> linked = new HashSet<>(jdbc.queryForList("SELECT es_doc_id FROM trace_event_link WHERE trace_id = ?",
                    String.class, r.get("trace_id")));
            List<String> evidence = Arrays.asList((String[]) ((Array) r.get("evidence_doc_ids")).getArray());
            assertThat(evidence).as("evidence of anomaly %s (%s)", r.get("id"), r.get("rule_id")).isNotEmpty();
            assertThat(linked).as("evidence of anomaly %s (%s)", r.get("id"), r.get("rule_id")).containsAll(evidence);
        }

        // Re-assembly re-runs the rules without duplicating findings.
        int before = jdbc.queryForObject("SELECT count(*) FROM anomaly", Integer.class);
        traces.getOrAssemble(normalRef, true);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM anomaly", Integer.class)).isEqualTo(before);

        // The trace API and the graph badges show the anomaly.
        String devops = token(RoleName.BACKEND_DEVOPS_ENGINEER);
        JsonNode t = json.readTree(mvc.perform(get("/api/traces/" + normalRef).header("Authorization", "Bearer " + devops))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(t.get("anomalies")).anySatisfy(a -> {
            assertThat(a.get("ruleId").asText()).isEqualTo("R10");
            assertThat(a.get("ruleName").asText()).isEqualTo("Approval above limit");
            assertThat(a.get("status").asText()).isEqualTo("NEW");
            assertThat(a.get("evidenceDocIds")).isNotEmpty();
        });
        JsonNode g = json.readTree(mvc.perform(get("/api/traces/" + normalRef + "/graph").header("Authorization", "Bearer " + devops))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        long badged = 0;
        for (JsonNode n : g.get("nodes")) {
            badged += n.get("anomalyIds").size();
        }
        assertThat(badged).isPositive();
        JsonNode search = json.readTree(mvc.perform(get("/api/search/flows?flowRef=" + normalRef + "&hasAnomaly=true"
                        + "&from=2026-05-14T00:00:00Z&to=2026-05-17T00:00:00Z").header("Authorization", "Bearer " + devops))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(search.get("items")).singleElement().satisfies(i -> {
            assertThat(i.get("anomalyCount").asInt()).isPositive();
            assertThat(i.get("maxSeverity").asText()).isEqualTo("HIGH");
        });
    }

    private List<String> ruleIds(String flowRef) {
        StoredTrace s = traces.findStored(flowRef).orElseThrow();
        return jdbc.queryForList("SELECT rule_id FROM anomaly WHERE trace_id = ?", String.class, s.trace().getId());
    }
}
