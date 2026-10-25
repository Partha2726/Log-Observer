package com.ielop.platform.user;

import com.ielop.platform.support.IntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ielop.platform.support.TestUsers;
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
 * Covers RBAC enforcement on the admin-only user-provisioning API: the
 * backend must actually enforce role checks server-side, not merely hide
 * the UI (SR-02).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserControllerTest extends IntegrationTest {

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

    private User admin;
    private User devops;

    @BeforeEach
    void seed() {
        admin = TestUsers.createUser(userRepository, roleRepository, passwordEncoder,
                "rbac.admin." + UUID.randomUUID(), RoleName.ADMINISTRATOR, true);
        devops = TestUsers.createUser(userRepository, roleRepository, passwordEncoder,
                "rbac.devops." + UUID.randomUUID(), RoleName.BACKEND_DEVOPS_ENGINEER, true);
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void administratorCanListUsers() throws Exception {
        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + tokenFor(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    void devopsCannotListUsers() throws Exception {
        mockMvc.perform(get("/api/users").header("Authorization", "Bearer " + tokenFor(devops)))
                .andExpect(status().isForbidden());
    }

    @Test
    void administratorCanProvisionNewUser() throws Exception {
        String username = "provisioned." + UUID.randomUUID();
        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "email", username + "@example.com",
                                "password", "SomeStrongPassword1!",
                                "fullName", "Provisioned User",
                                "role", "BACKEND_DEVOPS_ENGINEER"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.roles[0]").value("BACKEND_DEVOPS_ENGINEER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    void devopsCannotProvisionNewUser() throws Exception {
        String username = "should-not-be-created." + UUID.randomUUID();
        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + tokenFor(devops))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", username,
                                "email", username + "@example.com",
                                "password", "SomeStrongPassword1!",
                                "fullName", "Should Not Exist",
                                "role", "BACKEND_DEVOPS_ENGINEER"))))
                .andExpect(status().isForbidden());

        org.assertj.core.api.Assertions.assertThat(userRepository.existsByUsername(username)).isFalse();
    }

    @Test
    void duplicateUsernameIsRejectedWithBadRequest() throws Exception {
        mockMvc.perform(post("/api/users")
                        .header("Authorization", "Bearer " + tokenFor(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", admin.getUsername(),
                                "email", "different." + UUID.randomUUID() + "@example.com",
                                "password", "SomeStrongPassword1!",
                                "fullName", "Duplicate",
                                "role", "BACKEND_DEVOPS_ENGINEER"))))
                .andExpect(status().isBadRequest());
    }

    private String tokenFor(User user) throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", user.getUsername(),
                                "password", TestUsers.RAW_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).get("accessToken").asText();
    }
}
