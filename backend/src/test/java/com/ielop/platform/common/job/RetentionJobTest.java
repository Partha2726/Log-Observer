package com.ielop.platform.common.job;

import com.ielop.platform.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Nightly retention (T30, section 9.2): only the data past its retention goes, and the run is audited. */
class RetentionJobTest extends IntegrationTest {

    @Autowired
    private RetentionJob retention;
    @Autowired
    private JdbcTemplate jdbc;

    private long anomaly(String status, String age) {
        return jdbc.queryForObject("""
                INSERT INTO anomaly (rule_id, severity, detected_at, details, evidence_doc_ids, status, dedup_key)
                VALUES ('R12', 'LOW', now() - CAST(? AS interval), '{}'::jsonb, ARRAY['d'], ?, ?) RETURNING id""",
                Long.class, age, status, "ret:" + UUID.randomUUID());
    }

    private boolean exists(String table, Object id) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE id = ?", Integer.class, id) == 1;
    }

    @Test
    void removesOnlyExpiredRows() {
        long oldDismissed = anomaly("DISMISSED", "400 days");
        long oldConfirmed = anomaly("CONFIRMED", "400 days");
        long recentDismissed = anomaly("DISMISSED", "10 days");
        UUID oldConversation = UUID.randomUUID();
        UUID newConversation = UUID.randomUUID();
        jdbc.update("INSERT INTO ai_conversation (id, user_id, created_at) VALUES (?, 'ret.user', now() - interval '120 days')", oldConversation);
        jdbc.update("INSERT INTO ai_conversation (id, user_id, created_at) VALUES (?, 'ret.user', now())", newConversation);
        jdbc.update("INSERT INTO ai_message (conversation_id, role, content, created_at) VALUES (?, 'user', 'old', now() - interval '120 days')", oldConversation);
        jdbc.update("INSERT INTO ai_message (conversation_id, role, content, created_at) VALUES (?, 'user', 'new', now())", newConversation);
        jdbc.update("INSERT INTO ai_token_usage (user_id, day, tokens) VALUES ('ret.user', current_date - 100, 5), ('ret.user', current_date, 7)");

        var deleted = retention.run();

        assertThat(exists("anomaly", oldDismissed)).isFalse();
        assertThat(exists("anomaly", oldConfirmed)).as("CONFIRMED is kept").isTrue();
        assertThat(exists("anomaly", recentDismissed)).isTrue();
        assertThat(exists("ai_conversation", oldConversation)).isFalse();
        assertThat(exists("ai_conversation", newConversation)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_message WHERE conversation_id = ?", Integer.class, newConversation)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT tokens FROM ai_token_usage WHERE user_id = 'ret.user'", Long.class)).containsExactly(7L);
        assertThat(deleted.get("anomaly")).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action = 'RETENTION_RUN' AND actor = 'system'", Integer.class)).isPositive();
    }
}
