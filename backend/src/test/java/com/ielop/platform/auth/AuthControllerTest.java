package com.ielop.platform.auth;

import com.ielop.platform.support.IntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ielop.platform.auth.security.AppUserPrincipal;
import com.ielop.platform.auth.security.JwtService;
import com.ielop.platform.support.TestUsers;
import com.ielop.platform.user.RoleName;
import com.ielop.platform.user.RoleRepository;
import com.ielop.platform.user.User;
import com.ielop.platform.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers: valid login,
 * invalid credentials, malformed requests, a disabled user, valid/invalid
 * JWTs, and {@code /me} with and without authentication.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private User activeUser;

    @BeforeEach
    void seed() {
        activeUser = TestUsers.createUser(
                userRepository, roleRepository, passwordEncoder, "auth.active." + UUID.randomUUID(), RoleName.BACKEND_DEVOPS_ENGINEER, true);
        TestUsers.createUser(
                userRepository, roleRepository, passwordEncoder, "auth.disabled." + UUID.randomUUID(), RoleName.BACKEND_DEVOPS_ENGINEER, false);
    }

    @Test
    void validLoginReturnsTokenAndSafeUserInfo() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", activeUser.getUsername(),
                                "password", TestUsers.RAW_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.user.username").value(activeUser.getUsername()))
                .andExpect(jsonPath("$.user.roles[0]").value("BACKEND_DEVOPS_ENGINEER"))
                // The response must never leak the password or its hash.
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(content -> {
                    String body = content.getResponse().getContentAsString();
                    org.assertj.core.api.Assertions.assertThat(body).doesNotContain(TestUsers.RAW_PASSWORD);
                });
    }

    @Test
    void invalidPasswordIsRejectedWithoutRevealingWhichFieldWasWrong() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", activeUser.getUsername(),
                                "password", "definitely-wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsStringIgnoringCase("user"))));
    }

    @Test
    void unknownUsernameGetsSameGenericRejectionAsWrongPassword() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "no-such-user-" + UUID.randomUUID(),
                                "password", "whatever123"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedLoginRequestIsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", ""))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void disabledUserCannotLogIn() throws Exception {
        User disabled = userRepository.findAll().stream()
                .filter(u -> u.getUsername().startsWith("auth.disabled."))
                .findFirst()
                .orElseThrow();

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", disabled.getUsername(),
                                "password", TestUsers.RAW_PASSWORD))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meWithoutTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meWithValidTokenReturnsCurrentUser() throws Exception {
        String token = loginAndGetToken(activeUser.getUsername());

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(activeUser.getUsername()))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void meWithTamperedTokenIsRejected() throws Exception {
        String token = loginAndGetToken(activeUser.getUsername());
        String tampered = token.substring(0, token.length() - 2) + "xx";

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meWithExpiredTokenIsRejected() throws Exception {
        JwtService expiredService = new JwtService(
                "test-only-jwt-signing-secret-fixed-value-not-used-anywhere-else-0123456789",
                -1_000,
                "ielop-platform-test");
        AppUserPrincipal principal = new AppUserPrincipal(activeUser);
        String expiredToken = expiredService.generateToken(principal);

        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + expiredToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("API_ERROR"))
                .andExpect(jsonPath("$.message").value("Invalid or expired authentication token."));
    }

    @Test
    void meWithMalformedAuthorizationHeaderIsRejected() throws Exception {
        mockMvc.perform(get("/api/auth/me").header("Authorization", "NotBearer abc.def.ghi"))
                .andExpect(status().isUnauthorized());
    }

    private String loginAndGetToken(String username) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "password", TestUsers.RAW_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }
}
