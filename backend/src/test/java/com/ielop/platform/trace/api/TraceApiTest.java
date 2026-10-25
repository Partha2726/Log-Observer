package com.ielop.platform.trace.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Search, user activity, request and trace APIs against the demo fixture (T14). */
@AutoConfigureMockMvc
class TraceApiTest extends ElasticsearchIntegrationTest {

    static final String RANGE = "from=2026-05-14T00:00:00Z&to=2026-05-17T00:00:00Z";

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
    private final ObjectMapper json = new ObjectMapper();
    private String devops;
    private String admin;

    @BeforeEach
    void tokens() {
        devops = jwt.generateToken(new AppUserPrincipal(TestUsers.createUser(users, roles, encoder,
                "api.devops." + UUID.randomUUID(), RoleName.BACKEND_DEVOPS_ENGINEER, true)));
        admin = jwt.generateToken(new AppUserPrincipal(TestUsers.createUser(users, roles, encoder,
                "api.admin." + UUID.randomUUID(), RoleName.ADMINISTRATOR, true)));
    }

    static JsonNode flow(String scenario) {
        return EsFixtures.demoFlows().stream().filter(f -> f.get("scenario").asText().equals(scenario)).findFirst().orElseThrow();
    }

    private ResultActions call(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.header("Authorization", "Bearer " + devops));
    }

    private JsonNode body(MockHttpServletRequestBuilder request) throws Exception {
        return json.readTree(call(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    // --------------------------------------------------------------- API-001

    @Test
    void healthIsPublicAndActuatorComponentsAreForAdministrators() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.elasticsearch.status").value("UP"));
    }

    // --------------------------------------------------------------- API-002, API-003

    @Test
    void traceContractAndNotFoundEnvelope() throws Exception {
        String ref = flow("FAIL_DB_TIMEOUT_APPROVE").get("flowRef").asText();
        JsonNode t = body(get("/api/traces/" + ref));
        for (String field : List.of("flowRef", "flowId", "overallStatus", "actors", "requester", "approver", "executor", "amount",
                "currency", "counterpartyLast4", "firstSeen", "lastSeen", "durationMs", "stages", "firstFailure", "anomalies",
                "observedRatio", "excludedCount", "assembledAt", "assemblyVersion")) {
            assertThat(t.has(field)).as(field).isTrue();
        }
        assertThat(t.get("overallStatus").asText()).isEqualTo("FAILED");
        assertThat(t.get("firstFailure").get("component").asText()).isEqualTo("c.a.b.l.LimitCheck");
        assertThat(t.get("stages").get(2).get("status").asText()).isEqualTo("FAILED");
        assertThat(t.get("firstSeen").asText()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z");
        assertThat(t.get("actors").get("requester").asText()).isEqualTo(flow("FAIL_DB_TIMEOUT_APPROVE").get("requester").asText());

        call(get("/api/traces/REQ-20260515-999999"))
                .andExpect(status().isNotFound())
                .andExpect(header().exists("X-Request-ID"))
                .andExpect(jsonPath("$.code").value("TRACE_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").exists())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.path").value("/api/traces/REQ-20260515-999999"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.requestId").exists());
    }

    // --------------------------------------------------------------- API-004

    @Test
    void flowSearchFiltersNarrowAndUnassembledHitsAreMarked() throws Exception {
        JsonNode failed = flow("FAIL_DB_TIMEOUT_APPROVE");
        JsonNode rejected = flow("APPROVER_REJECT");
        String failedRef = failed.get("flowRef").asText();
        body(get("/api/traces/" + failedRef));
        body(get("/api/traces/" + rejected.get("flowRef").asText()));

        JsonNode byUser = body(get("/api/search/flows?userId=" + failed.get("requester").asText() + "&" + RANGE));
        assertThat(refs(byUser)).contains(failedRef);
        JsonNode item = item(byUser, failedRef);
        for (String field : List.of("flowRef", "flowId", "overallStatus", "actors", "requester", "approver", "executor", "firstSeen",
                "lastSeen", "amount", "currency", "anomalyCount", "maxSeverity", "assembled")) {
            assertThat(item.has(field)).as(field).isTrue();
        }
        assertThat(item.get("assembled").asBoolean()).isTrue();

        assertThat(refs(body(get("/api/search/flows?flowRef=" + failedRef + "&" + RANGE)))).containsExactly(failedRef);
        assertThat(refs(body(get("/api/search/flows?status=FAILED&" + RANGE)))).contains(failedRef).doesNotContain(rejected.get("flowRef").asText());
        assertThat(refs(body(get("/api/search/flows?stage=Approve&status=FAILED&" + RANGE)))).contains(failedRef);
        assertThat(refs(body(get("/api/search/flows?status=REJECTED&" + RANGE)))).contains(rejected.get("flowRef").asText());
        assertThat(refs(body(get("/api/search/flows?flowRef=" + failedRef + "&from=2026-06-01T00:00:00Z&to=2026-06-02T00:00:00Z")))).isEmpty();
        // The failed flow has an R05 finding after assembly; hasAnomaly=true and =false split the assembled flows.
        List<String> withAnomaly = refs(body(get("/api/search/flows?hasAnomaly=true&size=100&" + RANGE)));
        List<String> without = refs(body(get("/api/search/flows?hasAnomaly=false&size=100&" + RANGE)));
        assertThat(withAnomaly).contains(failedRef).doesNotContainAnyElementsOf(without);
        assertThat(item(body(get("/api/search/flows?flowRef=" + failedRef + "&" + RANGE)), failedRef).get("anomalyCount").asInt()).isPositive();

        // A flow that was never assembled is found through Elasticsearch, marked assembled: false.
        String unassembled = flow("FAIL_DB_DOWN_CREATE").get("flowRef").asText();
        JsonNode viaEs = body(get("/api/search/flows?flowRef=" + unassembled + "&" + RANGE));
        if (!item(viaEs, unassembled).get("assembled").asBoolean()) {
            assertThat(item(viaEs, unassembled).get("overallStatus").isNull()).isTrue();
        }
        String rid = EsFixtures.demoDocs().stream().filter(d -> d.list("flow.refs").contains(failedRef) && d.text("http.request.id") != null)
                .findFirst().orElseThrow().text("http.request.id");
        assertThat(refs(body(get("/api/search/flows?requestId=" + rid + "&" + RANGE)))).contains(failedRef);
    }

    private static List<String> refs(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("items").forEach(i -> out.add(i.get("flowRef").asText()));
        return out;
    }

    private static JsonNode item(JsonNode page, String ref) {
        for (JsonNode i : page.get("items")) {
            if (i.get("flowRef").asText().equals(ref)) {
                return i;
            }
        }
        throw new AssertionError(ref + " not in page");
    }

    // --------------------------------------------------------------- API-005, API-010

    @Test
    void invalidInputIsRejectedBeforeAnyQuery() throws Exception {
        call(get("/api/traces/NOT-A-REF")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR")).andExpect(jsonPath("$.fieldErrors.flowRef").exists());
        call(get("/api/requests/not-a-uuid")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.requestId").exists());
        call(get("/api/users/" + "u".repeat(70) + "/activity")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.userId").exists());
        call(get("/api/search/flows?size=500")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.size").exists());
        call(get("/api/search/flows?page=-1")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.page").exists());
        call(get("/api/search/logs?from=2026-05-10T00:00:00Z&to=2026-05-01T00:00:00Z")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.from").exists());
        call(get("/api/search/logs?from=2026-01-01T00:00:00Z&to=2026-05-01T00:00:00Z")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.to").exists());
        call(get("/api/search/logs?from=yesterday")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.from").exists());
        call(get("/api/search/flows?status=BROKEN")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.status").exists());
        call(get("/api/search/logs?stage=Nope")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.stage").exists());
        call(get("/api/traces/" + flow("NORMAL").get("flowRef").asText() + "/events?evidenceClass=GUESS"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.evidenceClass").exists());
    }

    // --------------------------------------------------------------- API-006

    @Test
    void userActivityShowsSessionsWithTheirEndAndRolesPerFlow() throws Exception {
        JsonNode timeout = flow("SESSION_TIMEOUT");
        body(get("/api/traces/" + timeout.get("flowRef").asText()));
        JsonNode a = body(get("/api/users/" + timeout.get("requester").asText() + "/activity?" + RANGE));
        for (String field : List.of("userId", "sessions", "failedSignIns", "flows", "permissionDenials", "anomalies")) {
            assertThat(a.has(field)).as(field).isTrue();
        }
        Set<String> reasons = new HashSet<>();
        a.get("sessions").forEach(s -> reasons.add(s.get("endReason").asText()));
        assertThat(reasons).contains("EXPIRED");
        JsonNode session = a.get("sessions").get(0);
        for (String field : List.of("sessionId", "loginAt", "logoutAt", "endReason", "clientIp", "failedAttemptsBefore")) {
            assertThat(session.has(field)).as(field).isTrue();
        }
        JsonNode timeoutFlow = item(wrap(a.get("flows")), timeout.get("flowRef").asText());
        assertThat(timeoutFlow.get("role").asText()).isEqualTo("requester");

        JsonNode normal = flow("NORMAL");
        body(get("/api/traces/" + normal.get("flowRef").asText()));
        JsonNode approver = body(get("/api/users/" + normal.get("approver").asText() + "/activity?" + RANGE));
        assertThat(item(wrap(approver.get("flows")), normal.get("flowRef").asText()).get("role").asText()).isEqualTo("approver");
        Set<String> approverReasons = new HashSet<>();
        approver.get("sessions").forEach(s -> approverReasons.add(s.get("endReason").asText()));
        assertThat(approverReasons).contains("LOGOUT");

        // A sign-in with no sign-out or expiry yet is OPEN.
        EsFixtures.Doc login = EsFixtures.demoDocs().stream().filter(d -> "LOGIN_OK".equals(d.text("event.action"))
                && normal.get("requester").asText().equals(d.text("user.id"))).findFirst().orElseThrow();
        ObjectNode open = login.source().deepCopy();
        open.with("session").put("id", "sess_00000000000open1");
        open.with("log").put("offset", 88_888_888L);
        index(List.of(new EsFixtures.Doc("open-session-doc", login.index(), open, login.truth())));
        try {
            JsonNode withOpen = body(get("/api/users/" + normal.get("requester").asText() + "/activity?" + RANGE));
            assertThat(withOpen.get("sessions").findValuesAsText("endReason")).contains("OPEN");
        } finally {
            deleteDocs(login.index(), List.of("open-session-doc"));
        }
    }

    private JsonNode wrap(JsonNode array) {
        ObjectNode page = json.createObjectNode();
        page.set("items", array);
        return page;
    }

    // --------------------------------------------------------------- API-007, TRACE-033

    @Test
    void evidenceFiltersByStageClassAndNodeAndFetchesTextByDocumentId() throws Exception {
        String ref = flow("FAIL_DB_TIMEOUT_APPROVE").get("flowRef").asText();
        JsonNode trace = body(get("/api/traces/" + ref));
        int total = trace.get("linkCount").asInt();

        JsonNode all = body(get("/api/traces/" + ref + "/events?size=100"));
        assertThat(all.get("totalElements").asInt()).isEqualTo(total);
        JsonNode line = all.get("items").get(0);
        for (String field : List.of("docId", "ts", "service", "host", "stage", "sourceFile", "level", "logger", "thread", "requestId",
                "evidenceClass", "confidence", "linkReason", "message", "parsed", "isStacktrace")) {
            assertThat(line.has(field)).as(field).isTrue();
        }
        assertThat(line.get("message").asText()).isNotBlank();

        JsonNode approve = body(get("/api/traces/" + ref + "/events?stage=Approve&size=100"));
        approve.get("items").forEach(i -> assertThat(i.get("stage").asText()).isEqualTo("Approve"));
        JsonNode correlated = body(get("/api/traces/" + ref + "/events?evidenceClass=CORRELATED&size=100"));
        correlated.get("items").forEach(i -> assertThat(i.get("evidenceClass").asText()).isEqualTo("CORRELATED"));
        assertThat(approve.get("items").findValuesAsText("isStacktrace")).contains("true");

        JsonNode graph = body(get("/api/traces/" + ref + "/graph"));
        JsonNode node = null;
        for (JsonNode n : graph.get("nodes")) {
            if (n.get("isFirstFailure").asBoolean()) {
                node = n;
            }
        }
        assertThat(node).isNotNull();
        JsonNode byNode = body(get("/api/traces/" + ref + "/events?nodeId=" + node.get("id").asText()));
        List<String> evidenceIds = new ArrayList<>();
        node.get("evidenceIds").forEach(e -> evidenceIds.add(e.asText()));
        assertThat(byNode.get("items").findValuesAsText("docId")).containsExactlyInAnyOrderElementsOf(evidenceIds);
        call(get("/api/traces/" + ref + "/events?nodeId=n-Approve-99")).andExpect(status().isNotFound());

        // TRACE-033: a document deleted by ILM shows as an expired row; the trace still answers.
        String gone = byNode.get("items").get(0).get("docId").asText();
        EsFixtures.Doc doc = EsFixtures.demoDocs().stream().filter(d -> d.id().equals(gone)).findFirst().orElseThrow();
        deleteDocs(doc.index(), List.of(gone));
        try {
            JsonNode expired = body(get("/api/traces/" + ref + "/events?nodeId=" + node.get("id").asText()));
            assertThat(expired.get("items").get(0).get("expired").asBoolean()).isTrue();
            assertThat(expired.get("items").get(0).get("message").isNull()).isTrue();
            call(get("/api/traces/" + ref)).andExpect(status().isOk());
        } finally {
            index(List.of(doc));
        }
    }

    // --------------------------------------------------------------- graph

    @Test
    void graphEndpointReturnsGraphVersionOne() throws Exception {
        String ref = flow("APPROVER_REJECT").get("flowRef").asText();
        body(get("/api/traces/" + ref));
        JsonNode g = body(get("/api/traces/" + ref + "/graph"));
        assertThat(g.get("graph_version").asInt()).isEqualTo(1);
        assertThat(g.get("lanes")).hasSize(4);
        assertThat(g.get("overallStatus").asText()).isEqualTo("REJECTED");
        assertThat(g.get("nodes").findValuesAsText("status")).contains("REJECTED", "NOT_REACHED");
    }

    // --------------------------------------------------------------- API-009

    @Test
    void logSearchFreeTextWithFiltersHighlightsAndDefaultsToSevenDays() throws Exception {
        JsonNode page = body(get("/api/search/logs?q=SQLTimeoutException&" + RANGE));
        assertThat(page.get("totalElements").asInt()).isGreaterThan(0);
        JsonNode first = page.get("items").get(0);
        for (String field : List.of("docId", "ts", "service", "host", "stage", "sourceFile", "level", "logger", "thread", "requestId",
                "flowRefs", "userId", "eventAction", "httpStatus", "snippet", "highlight")) {
            assertThat(first.has(field)).as(field).isTrue();
        }
        assertThat(page.get("items").findValuesAsText("highlight")).anyMatch(h -> h.contains("<em>"));

        JsonNode errors = body(get("/api/search/logs?level=ERROR&service=billing-service&" + RANGE));
        errors.get("items").forEach(i -> {
            assertThat(i.get("level").asText()).isEqualTo("ERROR");
            assertThat(i.get("service").asText()).isEqualTo("billing-service");
        });
        JsonNode approveLines = body(get("/api/search/logs?stage=Approve&size=100&" + RANGE));
        assertThat(approveLines.get("totalElements").asInt()).isGreaterThan(0);
        approveLines.get("items").forEach(i -> assertThat(i.get("stage").asText()).isEqualTo("Approve"));

        // No range: the last 7 days, which hold none of the fixture's May 2026 lines.
        assertThat(body(get("/api/search/logs?q=SQLTimeoutException")).get("totalElements").asInt()).isZero();
    }

    // --------------------------------------------------------------- API-013

    @Test
    void requestLookupResolvesToTheFlowOrReturnsTheRequestsOwnLines() throws Exception {
        String ref = flow("NORMAL").get("flowRef").asText();
        String rid = EsFixtures.demoDocs().stream().filter(d -> d.list("flow.refs").contains(ref) && d.text("http.request.id") != null)
                .findFirst().orElseThrow().text("http.request.id");
        JsonNode inFlow = body(get("/api/requests/" + rid));
        assertThat(inFlow.get("flowRef").asText()).isEqualTo(ref);
        assertThat(inFlow.get("lines").size()).isGreaterThan(0);

        Set<String> flowRequests = new HashSet<>();
        EsFixtures.demoDocs().stream().filter(d -> !d.list("flow.refs").isEmpty() && d.text("http.request.id") != null)
                .forEach(d -> flowRequests.add(d.text("http.request.id")));
        EsFixtures.Doc noFlow = EsFixtures.demoDocs().stream().filter(d -> d.truthFlowRef() == null && d.text("http.request.id") != null
                && !flowRequests.contains(d.text("http.request.id"))).findFirst().orElse(null);
        if (noFlow != null) {
            JsonNode lookup = body(get("/api/requests/" + noFlow.text("http.request.id")));
            assertThat(lookup.get("flowRef").isNull()).isTrue();
            assertThat(lookup.get("lines").size()).isGreaterThan(0);
        }
        call(get("/api/requests/" + UUID.randomUUID())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // --------------------------------------------------------------- definitions

    @Test
    void flowDefinitionsAreListed() throws Exception {
        assertThat(body(get("/api/flows/definitions")).get(0).get("id").asText()).isEqualTo("purchase-request");
    }
}
