package com.ielop.platform.flowdef;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /api/flows/definitions} (section 8.2). */
@AutoConfigureMockMvc
class FlowDefinitionControllerTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private RoleRepository roleRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void listsTheLoadedDefinitionsWithStagesInOrder() throws Exception {
        var user = TestUsers.createUser(userRepository, roleRepository, passwordEncoder,
                "flowdef." + UUID.randomUUID(), RoleName.BACKEND_DEVOPS_ENGINEER, true);
        String token = jwtService.generateToken(new AppUserPrincipal(user));

        mockMvc.perform(get("/api/flows/definitions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("purchase-request"))
                .andExpect(jsonPath("$[0].stages[0].name").value("SignIn"))
                .andExpect(jsonPath("$[0].stages[3].name").value("Execute"))
                .andExpect(jsonPath("$[0].stages[2].canReject").value(true))
                .andExpect(jsonPath("$[0].roles[1]").value("approver"))
                .andExpect(jsonPath("$[0].hasBusinessFields").value(true));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/flows/definitions")).andExpect(status().isUnauthorized());
    }
}
