package com.ielop.platform.health;

import com.ielop.platform.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SEC-001: every endpoint except login and health answers 401 without a token. */
@AutoConfigureMockMvc
class ApiAuthenticationTest extends IntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void everyEndpointButLoginAndHealthRequiresAToken() throws Exception {
        String ref = "REQ-20260515-000944";
        for (String path : List.of("/api/search/flows", "/api/search/logs", "/api/users/usr-1042/activity",
                "/api/requests/" + UUID.randomUUID(), "/api/traces/" + ref, "/api/traces/" + ref + "/events",
                "/api/traces/" + ref + "/graph", "/api/flows/definitions", "/api/users", "/api/auth/me",
                "/actuator/metrics", "/api/docs", "/api/no-such-endpoint")) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
        mvc.perform(post("/api/users").contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contentType("application/json").content("{}")).andExpect(status().isBadRequest());
    }
}
