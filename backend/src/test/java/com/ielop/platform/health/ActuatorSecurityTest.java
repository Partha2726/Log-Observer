package com.ielop.platform.health;

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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SEC-005: only health and info are public; every other actuator endpoint is ADMINISTRATOR only. */
@AutoConfigureMockMvc
class ActuatorSecurityTest extends IntegrationTest {

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
    void healthAndInfoArePublic() throws Exception {
        mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mockMvc.perform(get("/actuator/info")).andExpect(status().isOk());
    }

    @Test
    void metricsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    }

    @Test
    void metricsAreForbiddenForANonAdministrator() throws Exception {
        var devops = TestUsers.createUser(userRepository, roleRepository, passwordEncoder,
                "sec005.devops." + UUID.randomUUID(), RoleName.BACKEND_DEVOPS_ENGINEER, true);
        String token = jwtService.generateToken(new AppUserPrincipal(devops));

        mockMvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }
}
