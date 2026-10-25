package com.ielop.platform.trace.api;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.ielop.platform.auth.security.AppUserPrincipal;
import com.ielop.platform.auth.security.JwtService;
import com.ielop.platform.support.IntegrationTest;
import com.ielop.platform.support.TestUsers;
import com.ielop.platform.trace.repo.TraceRepository;
import com.ielop.platform.user.RoleName;
import com.ielop.platform.user.RoleRepository;
import com.ielop.platform.user.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Elasticsearch slow (F-02, API-021): the query times out, the API answers 503 and nothing partial is persisted. */
@AutoConfigureMockMvc
class TraceApiSlowEsTest extends IntegrationTest {

    static final WireMockServer SLOW_ES = new WireMockServer(options().dynamicPort());

    static {
        SLOW_ES.start();
        SLOW_ES.stubFor(any(anyUrl()).willReturn(aResponse().withFixedDelay(5_000).withStatus(200)
                .withHeader("Content-Type", "application/json").withHeader("X-Elastic-Product", "Elasticsearch")
                .withBody("{}")));
    }

    @DynamicPropertySource
    static void slowElasticsearch(DynamicPropertyRegistry registry) {
        registry.add("app.elasticsearch.url", SLOW_ES::baseUrl);
        registry.add("app.elasticsearch.timeout", () -> "1s");
    }

    @AfterAll
    static void stop() {
        SLOW_ES.stop();
    }

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
    private TraceRepository traces;

    @Test
    void aTimedOutAssemblyAnswers503AndWritesNothing() throws Exception {
        String token = jwt.generateToken(new AppUserPrincipal(TestUsers.createUser(users, roles, encoder,
                "slow." + UUID.randomUUID(), RoleName.BACKEND_DEVOPS_ENGINEER, true)));
        String ref = "REQ-20260515-777777";
        long start = System.nanoTime();
        mvc.perform(get("/api/traces/" + ref).header("Authorization", "Bearer " + token))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ES_UNAVAILABLE"));
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5_000);
        assertThat(traces.findByFlowRef(ref)).isEmpty();
    }
}
