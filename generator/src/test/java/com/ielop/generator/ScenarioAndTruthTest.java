package com.ielop.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GEN-005 (phase 1 scenarios) and GEN-006 (ground truth matches the files exactly) on one 1,000-flow run. */
class ScenarioAndTruthTest {

    static final Pattern D_START = Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3} \\| ");

    @TempDir
    static Path dir;
    static Path logs;
    static List<JsonNode> flows;
    static List<JsonNode> truthLines;
    /** Every log text (all lines of each event) grouped by the flowRef ground truth gives it. */
    static Map<String, List<String>> textByFlow = new HashMap<>();

    @BeforeAll
    static void generate() throws Exception {
        GeneratorRun.run(dir, "--flows", "1000", "--days", "1", "--seed", "42", "--legacy-timezone", "Asia/Kolkata");
        logs = dir.resolve("logs");
        flows = jsonl(dir.resolve("ground-truth/flows.jsonl"));
        truthLines = jsonl(dir.resolve("ground-truth/lines.jsonl"));

        Map<String, List<String>> fileLines = new HashMap<>();
        for (Path f : GeneratorRun.files(logs)) {
            fileLines.put(logs.relativize(f).toString(), Files.readAllLines(f, StandardCharsets.UTF_8));
        }
        for (JsonNode row : truthLines) {
            if (row.get("flowRef").isNull()) {
                continue;
            }
            List<String> lines = fileLines.get(row.get("file").asText());
            int i = row.get("lineNo").asInt() - 1;
            StringBuilder text = new StringBuilder(lines.get(i));
            for (int j = i + 1; j < lines.size() && row.get("file").asText().startsWith("billing-service/")
                    && !D_START.matcher(lines.get(j)).find(); j++) {
                text.append('\n').append(lines.get(j));
            }
            textByFlow.computeIfAbsent(row.get("flowRef").asText(), k -> new ArrayList<>()).add(text.toString());
        }
    }

    // ---------------------------------------------------------------- GEN-005

    @Test
    void everyScenarioIsPresentInTheDefaultMix() {
        Map<String, Integer> counts = new HashMap<>();
        flows.forEach(f -> counts.merge(f.get("scenario").asText(), 1, Integer::sum));
        assertEquals(850, counts.get("NORMAL"));
        for (com.ielop.generator.model.Scenario s : com.ielop.generator.model.Scenario.values()) {
            int n = counts.getOrDefault(s.name(), 0);
            // 15% spread over 17 scenarios: 8 or 9 slots each; DUPLICATE_SUBMIT writes two flows per slot.
            assertTrue(n >= 8, () -> s + " has " + n + " flows");
        }
    }

    @Test
    void eachScenarioHasItsSignature() {
        for (JsonNode f : flows) {
            String ref = f.get("flowRef").asText();
            String scenario = f.get("scenario").asText();
            String all = String.join("\n", textByFlow.get(ref));
            switch (scenario) {
                case "NORMAL" -> {
                    assertTrue(all.contains("\"event.action\":\"execution.completed\""), ref);
                    assertEquals("COMPLETED", f.get("overallStatus").asText());
                }
                case "FAIL_DB_TIMEOUT_APPROVE" -> {
                    assertTrue(all.contains("java.sql.SQLTimeoutException: Query timed out"), ref);
                    assertTrue(all.contains("/approve HTTP/1.1\" 500 "), ref);
                    assertFalse(all.contains("execution.started"), ref);
                    assertFailure(f, "Approve", "c.a.b.l.LimitCheck");
                }
                case "FAIL_DB_DOWN_CREATE" -> {
                    assertTrue(all.contains("\"error.type\":\"java.net.ConnectException\""), ref);
                    assertTrue(all.contains("\"POST /api/requests HTTP/1.1\" 503 "), ref);
                    assertFalse(all.contains("request.approval_started"), ref);
                    assertFailure(f, "Create", "com.acme.workflow.request.RequestService");
                }
                case "FAIL_VALIDATION_CREATE" -> {
                    assertTrue(all.contains("\"event.action\":\"request.validation_failed\""), ref);
                    assertTrue(all.contains("\"POST /api/requests HTTP/1.1\" 422 "), ref);
                    assertFalse(all.contains("request.created"), ref);
                    assertFailure(f, "Create", "gateway");
                }
                case "FAIL_PROVIDER_TIMEOUT_EXECUTE" -> {
                    assertTrue(all.contains("java.net.SocketTimeoutException: Read timed out"), ref);
                    assertTrue(all.contains("status=504"), ref);
                    assertTrue(all.contains("\"event.action\":\"execution.failed\""), ref);
                    assertFailure(f, "Execute", "c.a.b.i.PaymentProviderClient");
                }
                case "APPROVER_REJECT" -> {
                    assertTrue(all.contains("\"event.action\":\"request.rejected\""), ref);
                    assertTrue(all.contains("\"reason\":\"INSUFFICIENT_DOCS\""), ref);
                    assertFalse(all.contains("execution.started"), ref);
                    assertEquals("REJECTED", f.get("stages").get("Approve").asText());
                    assertEquals("NOT_REACHED", f.get("stages").get("Execute").asText());
                    assertEquals("REJECTED", f.get("overallStatus").asText());
                }
                case "SESSION_TIMEOUT" -> {
                    assertTrue(all.contains(" SESSION_EXPIRED ") && all.contains("idleMinutes=\"15\""), ref);
                    assertEquals("COMPLETED", f.get("overallStatus").asText());
                }
                case "SELF_APPROVAL" -> assertEquals(f.get("requester").asText(), f.get("approver").asText(), ref);
                case "EXECUTE_WITHOUT_APPROVAL" -> {
                    assertFalse(all.contains("request.approval_started"), ref);
                    assertTrue(all.contains("\"event.action\":\"execution.completed\""), ref);
                    assertEquals("NOT_REACHED", f.get("stages").get("Approve").asText());
                }
                case "OUT_OF_ORDER" -> assertTrue(all.indexOf("request.approved") < all.indexOf("request.form_opened"), ref);
                case "SLOW_APPROVE" -> assertTrue(all.matches("(?s).*Slow query: approval limit lookup took [234]\\d{4} ms.*"), ref);
                case "OFF_HOURS", "LIMIT_BREACH", "DUPLICATE_SUBMIT", "BRUTE_FORCE_LOGIN", "CONCURRENT_SESSIONS",
                     "AUTHZ_PROBING", "SCANNER_TRAFFIC" -> assertEquals("COMPLETED", f.get("overallStatus").asText(), ref);
                default -> throw new AssertionError("unexpected scenario " + scenario);
            }
            if (!scenario.startsWith("FAIL_")) {
                assertNull(f.get("expectedFailingStage").textValue(), ref);
            }
        }
    }

    private static void assertFailure(JsonNode flow, String stage, String component) {
        assertEquals("FAILED", flow.get("overallStatus").asText());
        assertEquals("FAILED", flow.get("stages").get(stage).asText());
        assertEquals(stage, flow.get("expectedFailingStage").asText());
        assertEquals(component, flow.get("expectedFailingComponent").asText());
    }

    // ---------------------------------------------------------------- GEN-006

    @Test
    void everyTruthRowPointsAtTheFirstLineOfAnEventAndCountsMatchTheFiles() throws Exception {
        Map<String, Integer> rowsPerFile = new HashMap<>();
        Set<String> seen = new java.util.HashSet<>();
        for (JsonNode row : truthLines) {
            String file = row.get("file").asText();
            int lineNo = row.get("lineNo").asInt();
            assertTrue(seen.add(file + ":" + lineNo), () -> "duplicate row " + row);
            rowsPerFile.merge(file, 1, Integer::sum);
        }
        for (Path f : GeneratorRun.files(logs)) {
            String rel = logs.relativize(f).toString();
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            int events = rel.startsWith("billing-service/")
                    ? (int) lines.stream().filter(l -> D_START.matcher(l).find()).count()
                    : lines.size();
            assertEquals(events, rowsPerFile.getOrDefault(rel, 0), rel);
            for (int i = 0; i < lines.size(); i++) {
                boolean isStart = !rel.startsWith("billing-service/") || D_START.matcher(lines.get(i)).find();
                assertEquals(isStart, seen.contains(rel + ":" + (i + 1)), () -> rel);
            }
        }
        assertEquals(rowsPerFile.keySet(), new java.util.HashSet<>(GeneratorRun.files(logs).stream()
                .map(p -> logs.relativize(p).toString()).toList()));
    }

    @Test
    void truthLabelsAreConsistent() {
        for (JsonNode row : truthLines) {
            boolean noise = row.get("isNoise").asBoolean();
            if (noise) {
                assertTrue(row.get("flowRef").isNull() && row.get("stage").isNull(), row::toString);
            }
            if (!row.get("flowRef").isNull()) {
                assertNotNull(row.get("stage").textValue(), row::toString);
            }
        }
    }

    @Test
    void usersFileListsRolesLimitsAndPermission() throws Exception {
        List<JsonNode> users = jsonl(dir.resolve("ground-truth/users.jsonl"));
        assertEquals(200, users.size());
        for (JsonNode u : users) {
            boolean approve = u.get("hasApprovePermission").asBoolean();
            assertEquals(approve, !u.get("limitAmount").isNull(), u::toString);
            assertTrue(u.get("email").asText().endsWith("@acme.example"));
            String[] name = u.get("name").asText().split(" ");
            assertTrue(com.ielop.generator.model.UserDirectory.FIRST_NAMES.contains(name[0]), "GEN-007 names");
            assertTrue(com.ielop.generator.model.UserDirectory.LAST_NAMES.contains(name[1]), "GEN-007 names");
        }
    }

    static List<JsonNode> jsonl(Path file) throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<JsonNode> rows = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            rows.add(json.readTree(line));
        }
        return rows;
    }
}
