package com.ielop.platform.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ielop.platform.auth.security.AppUserPrincipal;
import com.ielop.platform.auth.security.JwtService;
import com.ielop.platform.support.IntegrationTest;
import com.ielop.platform.support.TestUsers;
import com.ielop.platform.user.RoleName;
import com.ielop.platform.user.RoleRepository;
import com.ielop.platform.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Rate limits of section 8.10 (T30), with small limits so the test stays short. */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"app.rate-limits.login-per-minute=3", "app.rate-limits.assistant-messages-per-minute=2"})
class RateLimitTest extends IntegrationTest {

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

    private MockHttpServletRequestBuilder login(String ip, String body) {
        return post("/api/auth/login").with(r -> {
            r.setRemoteAddr(ip);
            return r;
        }).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void loginsPerClientAddressAreLimited() throws Exception {
        String body = json.writeValueAsString(Map.of("username", "nobody." + UUID.randomUUID(), "password", "Wrong-password-1"));
        for (int i = 0; i < 3; i++) {
            mvc.perform(login("10.9.9.9", body)).andExpect(status().isUnauthorized());
        }
        mvc.perform(login("10.9.9.9", body)).andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
        mvc.perform(login("10.9.9.10", body)).andExpect(status().isUnauthorized());
    }

    @Test
    void assistantMessagesPerUserAreLimited() throws Exception {
        String token = jwt.generateToken(new AppUserPrincipal(TestUsers.createUser(users, roles, encoder, "rl." + UUID.randomUUID(),
                RoleName.BACKEND_DEVOPS_ENGINEER, true)));
        String id = json.readTree(mvc.perform(post("/api/assistant/conversations").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("conversationId").asText();
        String msg = json.writeValueAsString(Map.of("message", "Any anomalies?"));
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/assistant/conversations/" + id + "/messages").header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content(msg)).andExpect(status().isOk());
        }
        mvc.perform(post("/api/assistant/conversations/" + id + "/messages").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(msg))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }
}
