package com.ielop.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GEN-002: every line matches its format in spec section 3. */
class FormatTest {

    /** Format A: nginx combined plus rid and rt. */
    static final Pattern FORMAT_A = Pattern.compile(
            "^\\S+ - \\S+ \\[\\d{2}/[A-Z][a-z]{2}/\\d{4}:\\d{2}:\\d{2}:\\d{2} [+-]\\d{4}] \"[A-Z]+ \\S+ HTTP/1\\.1\" \\d{3} (\\d+|-)"
                    + " \"[^\"]*\" \"[^\"]*\" rid=[0-9a-f-]{36} rt=\\d+\\.\\d{3}$");
    /** Format C: RFC 5424 header, structured data or '-', message. */
    static final Pattern FORMAT_C = Pattern.compile(
            "^<(\\d{1,3})>1 (\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z) (\\S+) (\\S+) (\\S+) (\\S+) "
                    + "(-|\\[(\\S+)((?: [A-Za-z0-9_]+=\"(?:[^\"\\\\\\]]|\\\\.)*\")*)]) (.*)$");
    /** Format D: start-of-event line. */
    static final Pattern FORMAT_D_START = Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3} \\| ");
    static final Pattern FORMAT_D = Pattern.compile(
            "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3} \\| (TRACE|DEBUG|INFO |WARN |ERROR|FATAL) \\| \\[[^\\]]+] \\| "
                    + "([0-9a-f-]{36})? \\| [\\w.$]+:\\w+\\(-?\\d+\\) \\| .*$");

    @TempDir
    static Path dir;
    static Path logs;

    @BeforeAll
    static void generate() throws Exception {
        GeneratorRun.run(dir, "--flows", "40", "--days", "1", "--seed", "42", "--legacy-timezone", "Asia/Kolkata");
        logs = dir.resolve("logs");
    }

    @Test
    void gatewayLinesAreCombinedLogFormat() throws Exception {
        List<String> lines = GeneratorRun.lines(logs, "gateway");
        assertFalse(lines.isEmpty());
        for (String line : lines) {
            assertTrue(FORMAT_A.matcher(line).matches(), () -> "format A: " + line);
        }
    }

    @Test
    void workflowLinesAreJsonWithEcsKeys() throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<String> lines = GeneratorRun.lines(logs, "workflow-service");
        assertFalse(lines.isEmpty());
        for (String line : lines) {
            JsonNode node = json.readTree(line);
            for (String key : List.of("@timestamp", "log.level", "service.name", "host.name", "process.thread.name",
                    "log.logger", "message")) {
                assertTrue(node.hasNonNull(key), () -> "missing " + key + " in " + line);
            }
            assertEquals("workflow-service", node.get("service.name").asText());
            assertTrue(node.get("@timestamp").asText().endsWith("Z"));
        }
    }

    @Test
    void authLinesAreRfc5424() throws Exception {
        List<String> lines = GeneratorRun.lines(logs, "auth-service");
        assertFalse(lines.isEmpty());
        for (String line : lines) {
            Matcher m = FORMAT_C.matcher(line);
            assertTrue(m.matches(), () -> "format C: " + line);
            int pri = Integer.parseInt(m.group(1));
            int facility = pri / 8;
            assertTrue(facility == 4 || facility == 10, () -> "facility auth or authpriv: " + line);
            assertEquals("acme-auth", m.group(4));
            if (!"-".equals(m.group(7))) {
                assertEquals("acme@32473", m.group(8));
            }
        }
    }

    @Test
    void billingEventsStartWithTheLog4jPattern() throws Exception {
        List<String> lines = GeneratorRun.lines(logs, "billing-service");
        assertFalse(lines.isEmpty());
        assertTrue(FORMAT_D_START.matcher(lines.get(0)).find(), "first line starts an event");
        for (String line : lines) {
            if (FORMAT_D_START.matcher(line).find()) {
                assertTrue(FORMAT_D.matcher(line).matches(), () -> "format D: " + line);
            }
        }
    }
}
