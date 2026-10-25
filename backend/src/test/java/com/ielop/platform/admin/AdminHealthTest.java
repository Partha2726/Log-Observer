package com.ielop.platform.admin;

import com.ielop.platform.auth.security.AppUserPrincipal;
import com.ielop.platform.auth.security.JwtService;
import com.ielop.platform.support.ElasticsearchIntegrationTest;
import com.ielop.platform.support.TestUsers;
import com.ielop.platform.user.RoleName;
import com.ielop.platform.user.RoleRepository;
import com.ielop.platform.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The admin health card (T30): components, ingestion counts and job queues for administrators only (SEC-002). */
@AutoConfigureMockMvc
class AdminHealthTest extends ElasticsearchIntegrationTest {

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

    @Test
    void administratorsSeeTheHealthCard() throws Exception {
        String admin = jwt.generateToken(new AppUserPrincipal(TestUsers.createUser(users, roles, encoder, "health." + UUID.randomUUID(),
                RoleName.ADMINISTRATOR, true)));
        String devops = jwt.generateToken(new AppUserPrincipal(TestUsers.createUser(users, roles, encoder, "health.d." + UUID.randomUUID(),
                RoleName.BACKEND_DEVOPS_ENGINEER, true)));
        mvc.perform(get("/api/admin/health").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.db.status").value("UP"))
                .andExpect(jsonPath("$.components.elasticsearch.status").value("UP"))
                .andExpect(jsonPath("$.components.ai.status").value("UP"))
                .andExpect(jsonPath("$.ingest.events").isNumber())
                .andExpect(jsonPath("$.ingest.parseFailures").value(0))
                .andExpect(jsonPath("$.alerts").isArray());
        mvc.perform(get("/api/admin/health").header("Authorization", "Bearer " + devops)).andExpect(status().isForbidden());
    }
}
