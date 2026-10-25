package com.ielop.platform.security;

import com.ielop.platform.assistant.ChatService;
import com.ielop.platform.assistant.SummaryService;
import com.ielop.platform.support.ElasticsearchIntegrationTest;
import com.ielop.platform.support.EsFixtures;
import com.ielop.platform.trace.TraceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SEC-006: application logs during AI work hold no prompt or evidence text (only counts, model, status).
 * SEC-004: no clear account number (a 12-digit 999… value) in any Postgres text or JSONB column.
 */
@ExtendWith(OutputCaptureExtension.class)
class LogPrivacyTest extends ElasticsearchIntegrationTest {

    static final Pattern CLEAR_ACCOUNT = Pattern.compile("(?<!\\d)999\\d{9}(?!\\d)");

    @Autowired
    private SummaryService summaries;
    @Autowired
    private ChatService chat;
    @Autowired
    private TraceService traces;
    @Autowired
    private JdbcTemplate jdbc;

    private static String ref(String scenario) {
        return EsFixtures.demoFlows().stream().filter(f -> f.get("scenario").asText().equals(scenario)).map(f -> f.get("flowRef").asText())
                .findFirst().orElseThrow();
    }

    @Test
    void aiWorkLogsNoPromptOrEvidenceText(CapturedOutput output) {
        String ref = ref("FAIL_DB_TIMEOUT_APPROVE");
        String user = "privacy." + UUID.randomUUID().toString().substring(0, 8);
        summaries.request(ref, true, user);
        while (summaries.runNext("privacy-worker").isPresent()) {
            // drain
        }
        UUID conversation = chat.create(ref, user);
        chat.message(conversation, user, "Why did the approval fail? secret-question-marker");

        String logs = output.getAll();
        assertThat(logs).contains("AI summary via fake/fake-1: ok").contains("AI chat via fake/fake-1: ok");
        assertThat(logs).doesNotContain("You are a debugging assistant", "<evidence", "<context>", "secret-question-marker");
        // No evidence line text either: the fixture's error message bodies never appear in the log.
        List<String> bodies = new ArrayList<>();
        EsFixtures.demoDocs().stream().filter(d -> d.list("flow.refs").contains(ref)).map(d -> d.text("message_body"))
                .filter(b -> b != null && b.length() > 40).forEach(bodies::add);
        assertThat(bodies).isNotEmpty();
        for (String b : bodies) {
            assertThat(logs).doesNotContain(b);
        }
    }

    @Test
    void noClearAccountNumberInAnyTextOrJsonColumn() {
        for (var f : EsFixtures.demoFlows()) {
            traces.getOrAssemble(f.get("flowRef").asText(), false);
        }
        List<Map<String, Object>> columns = jdbc.queryForList("""
                SELECT table_name, column_name FROM information_schema.columns
                 WHERE table_schema = 'public' AND data_type IN ('text', 'character varying', 'character', 'jsonb', 'json', 'ARRAY')
                   AND table_name <> 'flyway_schema_history'
                   AND column_name NOT IN ('es_doc_id', 'evidence_doc_ids', 'first_failure_doc_id', 'context_hash', 'config_hash', 'counterparty_hash')""");
        assertThat(columns).isNotEmpty();
        List<String> hits = new ArrayList<>();
        for (Map<String, Object> c : columns) {
            String sql = "SELECT count(*) FROM \"" + c.get("table_name") + "\" WHERE CAST(\"" + c.get("column_name") + "\" AS text) ~ '(^|[^0-9])999[0-9]{9}([^0-9]|$)'";
            Integer n = jdbc.queryForObject(sql, Integer.class);
            if (n != null && n > 0) {
                hits.add(c.get("table_name") + "." + c.get("column_name") + ": " + n);
            }
        }
        assertThat(hits).isEmpty();
        assertThat(CLEAR_ACCOUNT.matcher("x 999912345678 y").find()).isTrue();
    }
}
