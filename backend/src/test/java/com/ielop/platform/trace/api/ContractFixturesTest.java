package com.ielop.platform.trace.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ielop.platform.auth.security.AppUserPrincipal;
import com.ielop.platform.auth.security.JwtService;
import com.ielop.platform.support.ElasticsearchIntegrationTest;
import com.ielop.platform.support.EsFixtures;
import com.ielop.platform.support.TestUsers;
import com.ielop.platform.user.RoleName;
import com.ielop.platform.user.RoleRepository;
import com.ielop.platform.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Backend contract fixtures for the frontend's zod schemas (UI-007): real responses
 * of each endpoint, written to {@code frontend/src/test/contract}. This test fails when
 * a response no longer matches its fixture; regenerate with
 * {@code ./mvnw test -Dtest=ContractFixturesTest -DupdateContracts=true}, then run the
 * frontend tests to see whether the schemas still accept it.
 */
@AutoConfigureMockMvc
class ContractFixturesTest extends ElasticsearchIntegrationTest {

    static final Path DIR = Path.of("..", "frontend", "src", "test", "contract");
    static final String RANGE = TraceApiTest.RANGE;

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
    private final ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    @Test
    void responsesMatchTheCommittedContractFixtures() throws Exception {
        String token = jwt.generateToken(new AppUserPrincipal(TestUsers.createUser(users, roles, encoder,
                "contract." + UUID.randomUUID(), RoleName.BACKEND_DEVOPS_ENGINEER, true)));
        // Assemble every fixture flow first, so role and status fields do not depend on test order.
        for (JsonNode f : EsFixtures.demoFlows()) {
            fetch(token, "/api/traces/" + f.get("flowRef").asText(), 200);
        }
        JsonNode failed = TraceApiTest.flow("FAIL_DB_TIMEOUT_APPROVE");
        String ref = failed.get("flowRef").asText();
        String rid = EsFixtures.demoDocs().stream().filter(d -> d.list("flow.refs").contains(ref) && d.text("http.request.id") != null)
                .findFirst().orElseThrow().text("http.request.id");

        // Re-assemble once every flow is stored, so the rule baselines (R09) do not depend on test order.
        fetch(token, "/api/traces/" + ref + "?rebuild=true", 200);

        Map<String, JsonNode> fixtures = new LinkedHashMap<>();
        fixtures.put("trace", fetch(token, "/api/traces/" + ref, 200));
        fixtures.put("graph", fetch(token, "/api/traces/" + ref + "/graph", 200));
        renumberAnomalies(fixtures.get("trace"), fixtures.get("graph"));
        fixtures.put("events", fetch(token, "/api/traces/" + ref + "/events?stage=Approve&size=10", 200));
        fixtures.put("flows", fetch(token, "/api/search/flows?flowRef=" + ref + "&" + RANGE, 200));
        fixtures.put("logs", fetch(token, "/api/search/logs?q=SQLTimeoutException&size=5&" + RANGE, 200));
        fixtures.put("activity", fetch(token, "/api/users/" + failed.get("requester").asText() + "/activity?" + RANGE, 200));
        fixtures.put("request", fetch(token, "/api/requests/" + rid, 200));
        fixtures.put("definitions", fetch(token, "/api/flows/definitions", 200));
        fixtures.put("stats", stats(fetch(token, "/api/stats/overview?flowId=" + failed.get("flowId").asText() + "&" + RANGE, 200)));
        fixtures.put("anomalies", anomalies(fetch(token, "/api/anomalies?ruleId=R05&flowRef=" + ref + "&" + RANGE, 200)));
        fixtures.put("error-not-found", fetch(token, "/api/traces/REQ-20260515-999999", 404));
        fixtures.put("error-validation", fetch(token, "/api/search/flows?size=500", 400));

        boolean update = Boolean.getBoolean("updateContracts");
        Files.createDirectories(DIR);
        for (Map.Entry<String, JsonNode> e : fixtures.entrySet()) {
            Path file = DIR.resolve(e.getKey() + ".json");
            JsonNode normalized = normalize(e.getValue());
            if (update || !Files.exists(file)) {
                Files.writeString(file, json.writeValueAsString(normalized) + "\n", StandardCharsets.UTF_8);
            }
            assertThat(json.readTree(Files.readString(file, StandardCharsets.UTF_8)))
                    .as(file + " is stale; run ./mvnw test -Dtest=ContractFixturesTest -DupdateContracts=true")
                    .isEqualTo(normalized);
        }
    }

    private JsonNode fetch(String token, String path, int status) throws Exception {
        var result = mvc.perform(get(path).header("Authorization", "Bearer " + token)).andReturn().getResponse();
        assertThat(result.getStatus()).as(path).isEqualTo(status);
        return json.readTree(result.getContentAsString());
    }

    /** Other tests add anomalies to the same days; keep the rule every run produces (R05) for the shape. */
    private static JsonNode stats(JsonNode o) {
        List<JsonNode> keep = new ArrayList<>();
        o.get("anomaliesByRule").forEach(r -> {
            if ("R05".equals(r.get("ruleId").asText())) {
                keep.add(r);
            }
        });
        ((ObjectNode) o).putArray("anomaliesByRule").addAll(keep);
        return o;
    }

    /** Anomaly rows: IDs and detection times depend on the run. */
    private static JsonNode anomalies(JsonNode page) {
        int i = 0;
        for (JsonNode a : page.get("items")) {
            ((ObjectNode) a).put("id", ++i);
            ((ObjectNode) a).put("detectedAt", "2026-05-16T00:00:00.000Z");
        }
        return page;
    }

    /** Anomaly IDs depend on which tests ran first: number them 1, 2, … in trace order, in the trace and the graph. */
    private static void renumberAnomalies(JsonNode trace, JsonNode graph) {
        Map<Long, Integer> ids = new LinkedHashMap<>();
        for (JsonNode a : trace.get("anomalies")) {
            ids.put(a.get("id").asLong(), ids.size() + 1);
            ((ObjectNode) a).put("id", ids.size());
        }
        for (JsonNode n : graph.get("nodes")) {
            List<Integer> mapped = new ArrayList<>();
            n.get("anomalyIds").forEach(id -> mapped.add(ids.get(id.asLong())));
            ArrayNode renumbered = ((ObjectNode) n).putArray("anomalyIds");
            mapped.forEach(renumbered::add);
        }
    }

    /** Replaces values that change on every run (assembly time, error timestamp and request ID). */
    private static JsonNode normalize(JsonNode node) {
        JsonNode copy = node.deepCopy();
        if (copy instanceof ObjectNode o) {
            if (o.has("assembledAt")) {
                o.put("assembledAt", "2026-05-16T00:00:00.000Z");
            }
            if (o.has("requestId") && o.has("code")) {
                o.put("requestId", "00000000-0000-4000-8000-000000000000");
                o.put("timestamp", "2026-05-16T00:00:00.000Z");
            }
        }
        return copy;
    }
}
