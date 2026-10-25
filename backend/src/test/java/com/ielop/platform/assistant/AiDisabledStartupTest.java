package com.ielop.platform.assistant;

import com.ielop.platform.auth.security.AppUserPrincipal;
import com.ielop.platform.auth.security.JwtService;
import com.ielop.platform.common.exception.ApiException;
import com.ielop.platform.support.IntegrationTest;
import com.ielop.platform.support.TestUsers;
import com.ielop.platform.user.RoleName;
import com.ielop.platform.user.RoleRepository;
import com.ielop.platform.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AI-013: the {@code openai} provider without a key starts the application with AI {@code DISABLED}. */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"app.ai.provider=openai", "app.ai.base-url=https://provider.example/v1", "app.ai.api-key=",
        "app.ai.model=model-x"})
class AiDisabledStartupTest extends IntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AiGateway gateway;
    @Autowired
    private AiAvailability availability;
    @Autowired
    private AiHealthIndicator health;
    @Autowired
    private JwtService jwt;
    @Autowired
    private UserRepository users;
    @Autowired
    private RoleRepository roles;
    @Autowired
    private PasswordEncoder encoder;

    @Test
    void healthReportsDisabledAndEveryCallIsAiDisabled() throws Exception {
        assertThat(availability.enabled()).isFalse();
        assertThat(availability.reason()).isEqualTo("AI_API_KEY is not set");
        String admin = jwt.generateToken(new AppUserPrincipal(TestUsers.createUser(users, roles, encoder,
                "ai.admin." + UUID.randomUUID(), RoleName.ADMINISTRATOR, true)));
        mvc.perform(get("/actuator/health").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.ai.status").value("DISABLED"));
        // The test profile hides health details; the indicator carries them for administrators in other profiles.
        assertThat(health.health().getDetails()).containsEntry("reason", "AI_API_KEY is not set").containsEntry("provider", "openai");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/traces/REQ-20260515-000001/ai-summary")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AI_DISABLED"));
        assertThatThrownBy(() -> gateway.chat(new AiGateway.ChatRequest("s", List.of(), "hi", null, List.of())))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo("AI_DISABLED");
                    assertThat(e.status().value()).isEqualTo(503);
                });
    }
}
