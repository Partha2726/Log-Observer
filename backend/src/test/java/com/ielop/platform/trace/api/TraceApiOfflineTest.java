package com.ielop.platform.trace.api;

import com.ielop.platform.auth.security.AppUserPrincipal;
import com.ielop.platform.auth.security.JwtService;
import com.ielop.platform.flowdef.FlowDefinitionRegistry;
import com.ielop.platform.support.IntegrationTest;
import com.ielop.platform.support.TestUsers;
import com.ielop.platform.trace.TraceProperties;
import com.ielop.platform.trace.TraceStore;
import com.ielop.platform.trace.assembly.Assembler;
import com.ielop.platform.trace.assembly.Hits;
import com.ielop.platform.trace.assembly.InMemoryEvidenceSource;
import com.ielop.platform.user.RoleName;
import com.ielop.platform.user.RoleRepository;
import com.ielop.platform.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Elasticsearch down (F-01): stored traces, graphs and Postgres-backed searches keep
 * working; endpoints that need Elasticsearch answer 503 {@code ES_UNAVAILABLE}
 * (API-008, API-020).
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"app.elasticsearch.url=http://127.0.0.1:1", "app.elasticsearch.timeout=2s"})
class TraceApiOfflineTest extends IntegrationTest {

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
    private TraceStore store;
    @Autowired
    private FlowDefinitionRegistry flows;
    @Autowired
    private TransactionTemplate tx;
    private String token;
    private String storedRef;

    @BeforeEach
    void storeOneTrace() {
        token = jwt.generateToken(new AppUserPrincipal(TestUsers.createUser(users, roles, encoder,
                "offline." + UUID.randomUUID(), RoleName.BACKEND_DEVOPS_ENGINEER, true)));
        storedRef = TraceApiTest.flow("FAIL_PROVIDER_TIMEOUT_EXECUTE").get("flowRef").asText();
        var result = new Assembler(TraceProperties.defaults()).assemble(storedRef, flows.forFlowRef(storedRef).orElseThrow(),
                new InMemoryEvidenceSource(Hits.demo()));
        tx.executeWithoutResult(s -> store.save(result, Instant.now()));
    }

    @Test
    void storedTraceAndGraphAreServedFromPostgresAlone() throws Exception {
        mvc.perform(get("/api/traces/" + storedRef).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.overallStatus").value("FAILED"));
        mvc.perform(get("/api/traces/" + storedRef + "/graph").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.graph_version").value(1));
        mvc.perform(get("/api/search/flows?flowRef=" + storedRef + "&" + TraceApiTest.RANGE).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].flowRef").value(storedRef));
    }

    @Test
    void elasticsearchBackedEndpointsAnswer503() throws Exception {
        // A valid reference never stored by any test (the Postgres container is shared across test classes).
        String unassembled = "REQ-20260515-555555";
        for (String path : new String[]{"/api/search/logs?q=x&" + TraceApiTest.RANGE,
                "/api/users/usr-1001/activity?" + TraceApiTest.RANGE,
                "/api/search/flows?userId=usr-0000&" + TraceApiTest.RANGE,
                "/api/traces/" + unassembled,
                "/api/traces/" + storedRef + "/events",
                "/api/requests/" + UUID.randomUUID()}) {
            mvc.perform(get(path).header("Authorization", "Bearer " + token))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("ES_UNAVAILABLE"));
        }
    }
}
