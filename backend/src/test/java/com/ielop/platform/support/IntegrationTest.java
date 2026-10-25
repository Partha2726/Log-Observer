package com.ielop.platform.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for Spring integration tests (spec section 13).
 *
 * <p>One Postgres container per test JVM, started once and shared by every
 * subclass (the singleton-container pattern). The image is the same
 * {@code pgvector/pgvector:pg16} the stack runs, so Flyway, {@code SKIP LOCKED}
 * and pgvector behave exactly as in production. Ryuk removes the container when
 * the JVM exits.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTest {

    public static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres");

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("ielop_test")
            .withUsername("ielop")
            .withPassword("ielop");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
