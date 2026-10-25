package com.ielop.platform.db;

import com.ielop.platform.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** DB-001: every migration applies on an empty {@code pgvector/pgvector:pg16} container. */
class MigrationTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void allMigrationsApplySuccessfullyOnAnEmptyDatabase() {
        Integer failed = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = false", Integer.class);
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank", String.class);

        assertThat(failed).isZero();
        assertThat(versions).startsWith("1", "2", "3", "4", "5", "6");
    }

    @Test
    void pgvectorExtensionIsInstalled() {
        Integer vector = jdbc.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class);
        assertThat(vector).isEqualTo(1);
    }
}
