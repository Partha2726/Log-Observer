package com.ielop.platform.stats;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ielop.platform.auth.security.AppUserPrincipal;
import com.ielop.platform.auth.security.JwtService;
import com.ielop.platform.support.ElasticsearchIntegrationTest;
import com.ielop.platform.support.EsFixtures;
import com.ielop.platform.support.TestUsers;
import com.ielop.platform.trace.TraceService;
import com.ielop.platform.user.RoleName;
import com.ielop.platform.user.RoleRepository;
import com.ielop.platform.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /api/stats/overview} (T29): counts equal the ground truth and SQL over the demo fixture. */
@AutoConfigureMockMvc
class StatsTest extends ElasticsearchIntegrationTest {

    static final String RANGE = "from=2026-05-14T00:00:00Z&to=2026-05-17T00:00:00Z";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TraceService traces;
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

    private JsonNode overview(String query) throws Exception {
        String token = jwt.generateToken(new AppUserPrincipal(TestUsers.createUser(users, roles, encoder, "stats." + UUID.randomUUID(),
                RoleName.BACKEND_DEVOPS_ENGINEER, true)));
        return json.readTree(mvc.perform(get("/api/stats/overview?" + query)
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    @Test
    void countsMatchTheGroundTruthAndTheTables() throws Exception {
        Map<String, Long> truth = new TreeMap<>();
        for (JsonNode f : EsFixtures.demoFlows()) {
            traces.getOrAssemble(f.get("flowRef").asText(), true);
            truth.merge(f.get("overallStatus").asText(), 1L, Long::sum);
        }
        JsonNode o = overview(RANGE + "&flowId=purchase-request");

        Map<String, Long> byStatus = new TreeMap<>();
        o.get("byStatus").fields().forEachRemaining(e -> byStatus.put(e.getKey(), e.getValue().asLong()));
        Map<String, Long> sql = new TreeMap<>();
        jdbc.query("SELECT overall_status, count(*) FROM trace WHERE flow_id = 'purchase-request' "
                + "AND first_seen BETWEEN '2026-05-14T00:00:00Z' AND '2026-05-17T00:00:00Z' GROUP BY 1", rs -> {
                    sql.put(rs.getString(1), rs.getLong(2));
                });
        assertThat(byStatus).isEqualTo(sql);
        // Every fixture flow is counted with its ground-truth status (other tests may add rows on the same day).
        truth.forEach((status, n) -> assertThat(byStatus.getOrDefault(status, 0L)).as(status).isGreaterThanOrEqualTo(n));
        assertThat(o.get("flows").asLong()).isEqualTo(sql.values().stream().mapToLong(Long::longValue).sum());

        long perDay = 0;
        for (JsonNode d : o.get("perDay")) {
            perDay += d.get("completed").asLong() + d.get("failed").asLong() + d.get("rejected").asLong() + d.get("incomplete").asLong();
        }
        assertThat(perDay).isEqualTo(o.get("flows").asLong());

        String window = " FROM trace t WHERE t.first_seen BETWEEN '2026-05-14T00:00:00Z' AND '2026-05-17T00:00:00Z'";
        assertThat(o.get("byStage").get("Approve").get("FAILED").asLong()).isEqualTo(jdbc.queryForObject(
                "SELECT count(*) FROM trace_stage s JOIN trace t ON t.id = s.trace_id AND t.first_seen BETWEEN '2026-05-14T00:00:00Z' AND '2026-05-17T00:00:00Z' "
                        + "WHERE s.stage = 'Approve' AND s.status = 'FAILED'", Long.class));
        assertThat(o.get("topFailingComponents")).anySatisfy(c -> assertThat(c.get("logger").asText()).isEqualTo("c.a.b.l.LimitCheck"));
        long failedComponents = 0;
        for (JsonNode c : o.get("topFailingComponents")) {
            failedComponents += c.get("count").asLong();
        }
        assertThat(failedComponents).isEqualTo(jdbc.queryForObject("SELECT count(*)" + window + " AND t.first_failure_component IS NOT NULL", Long.class));
        assertThat(o.get("anomaliesByRule")).anySatisfy(r -> assertThat(r.get("ruleId").asText()).isEqualTo("R05"));
        assertThat(o.get("byService").get(0).get("errors").asLong()).isPositive();
        assertThat(o.get("timezone").asText()).isNotBlank();
    }

    @Test
    void anEmptyRangeAndBadParametersAreHandled() throws Exception {
        JsonNode empty = overview("from=2030-01-01T00:00:00Z&to=2030-01-02T00:00:00Z");
        assertThat(empty.get("flows").asLong()).isZero();
        assertThat(empty.get("perDay")).isEmpty();
        String token = jwt.generateToken(new AppUserPrincipal(TestUsers.createUser(users, roles, encoder, "stats." + UUID.randomUUID(),
                RoleName.BACKEND_DEVOPS_ENGINEER, true)));
        mvc.perform(get("/api/stats/overview?flowId=nope").header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/stats/overview?from=2026-01-01T00:00:00Z&to=2026-05-01T00:00:00Z").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }
}
