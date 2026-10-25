package com.ielop.platform;

import com.ielop.platform.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Verifies the Spring application context starts successfully with the
 * foundation configuration on the Testcontainers Postgres with Flyway (see IntegrationTest).
 */
@SpringBootTest
@ActiveProfiles("test")
class PlatformApplicationTests extends IntegrationTest {

    @Test
    void contextLoads() {
        // Intentionally empty: a failure to load the context fails this test.
    }
}
