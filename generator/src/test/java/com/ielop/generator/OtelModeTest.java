package com.ielop.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 3 OpenTelemetry mode (T32: GEN-001 and GEN-002 with {@code --otel true}). Same seed gives the same
 * output; the otel output is the plain output plus the trace fields only; every format carries them per
 * spec section 15; one trace per flow, one span per stage.
 */
class OtelModeTest {

    static final String[] ARGS = {"--flows", "40", "--days", "1", "--seed", "42", "--legacy-timezone", "Asia/Kolkata"};
    static final Pattern TID = Pattern.compile(" tid=([0-9a-f]{32}|-)$");
    static final Pattern D_TRACE = Pattern.compile("^(.*? \\| )([0-9a-f-]{36})? trace_id=([0-9a-f]{32})?( \\| .*)$");
    static final Pattern C_TRACE = Pattern.compile(" span=\"([0-9a-f]{16})\"| trace=\"([0-9a-f]{32})\"");

    @TempDir
    static Path plainDir;
    @TempDir
    static Path otelDir;
    static Path plain;
    static Path otel;

    @BeforeAll
    static void generate() throws Exception {
        GeneratorRun.run(plainDir, ARGS);
        String[] otelArgs = new String[ARGS.length + 2];
        System.arraycopy(ARGS, 0, otelArgs, 0, ARGS.length);
        otelArgs[ARGS.length] = "--otel";
        otelArgs[ARGS.length + 1] = "true";
        GeneratorRun.run(otelDir, otelArgs);
        plain = plainDir.resolve("logs");
        otel = otelDir.resolve("logs");
    }

    @Test
    void gen001SameSeedGivesTheSameOtelOutput(@TempDir Path again) throws Exception {
        GeneratorRun.run(again, "--flows", "40", "--days", "1", "--seed", "42", "--legacy-timezone", "Asia/Kolkata", "--otel", "true");
        assertEquals(GeneratorRun.treeHash(otel), GeneratorRun.treeHash(again.resolve("logs")));
        assertEquals(GeneratorRun.treeHash(otelDir.resolve("ground-truth")), GeneratorRun.treeHash(plainDir.resolve("ground-truth")),
                "the same lines in both modes, so the same ground truth");
    }

    @Test
    void otelOutputIsThePlainOutputPlusTraceFieldsOnly() throws Exception {
        for (String folder : List.of("gateway", "workflow-service", "auth-service", "billing-service")) {
            List<String> a = GeneratorRun.lines(plain, folder);
            List<String> b = GeneratorRun.lines(otel, folder);
            assertEquals(a.size(), b.size(), folder);
            for (int i = 0; i < a.size(); i++) {
                assertEquals(a.get(i), strip(folder, b.get(i)), folder + " line " + (i + 1));
            }
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String strip(String folder, String line) throws Exception {
        return switch (folder) {
            case "gateway" -> TID.matcher(line).replaceFirst("");
            case "billing-service" -> {
                Matcher m = D_TRACE.matcher(line);
                yield m.matches() ? m.group(1) + (m.group(2) == null ? "" : m.group(2)) + m.group(4) : line;
            }
            case "auth-service" -> C_TRACE.matcher(line).replaceAll("").replace(" [acme@32473]", " -");
            default -> {
                if (!line.startsWith("{")) {
                    yield line;
                }
                yield line.replaceAll(",\"trace\\.id\":\"[0-9a-f]{32}\",\"span\\.id\":\"[0-9a-f]{16}\"", "");
            }
        };
    }

    @Test
    void gen002EveryFormatCarriesTheTraceFieldsInItsOwnSyntax() throws Exception {
        List<String> gateway = GeneratorRun.lines(otel, "gateway");
        assertTrue(gateway.stream().allMatch(l -> TID.matcher(l).find()), "format A ends with tid=<trace id> or tid=-");
        assertTrue(gateway.stream().anyMatch(l -> l.matches(".* tid=[0-9a-f]{32}$")));

        List<String> billingStarts = GeneratorRun.lines(otel, "billing-service").stream()
                .filter(l -> FormatTest.FORMAT_D_START.matcher(l).find()).toList();
        assertTrue(billingStarts.stream().allMatch(l -> D_TRACE.matcher(l).matches()), "format D: ' trace_id=' after the request ID column");
        long legacyWithTrace = billingStarts.stream().map(D_TRACE::matcher).filter(Matcher::matches)
                .filter(m -> m.group(2) == null && m.group(3) != null).count();
        assertTrue(legacyWithTrace > 0, "billing lines without a request ID still carry the flow's trace ID");

        List<String> workflow = GeneratorRun.lines(otel, "workflow-service");
        assertTrue(workflow.stream().anyMatch(l -> l.contains("\"trace.id\":\"") && l.contains("\"span.id\":\"")));
        List<String> auth = GeneratorRun.lines(otel, "auth-service");
        assertTrue(auth.stream().anyMatch(l -> l.contains(" trace=\"") && l.contains(" span=\"")));
        assertTrue(auth.stream().allMatch(l -> FormatTest.FORMAT_C.matcher(l).matches()), "format C stays valid RFC 5424");
    }

    @Test
    void oneTracePerFlowAndOneSpanPerStage() throws Exception {
        Map<String, String> traceOfFlow = new HashMap<>();
        Map<String, Set<String>> spansOfFlow = new HashMap<>();
        Pattern ref = Pattern.compile("\"flow\\.ref\":\"(REQ-[0-9-]+)\"");
        for (String l : GeneratorRun.lines(otel, "workflow-service")) {
            Matcher r = ref.matcher(l);
            if (!r.find() || !l.contains("\"trace.id\"")) {
                continue;
            }
            JsonNode n = JSON.readTree(l);
            String flow = r.group(1);
            String previous = traceOfFlow.putIfAbsent(flow, n.get("trace.id").asText());
            if (previous != null) {
                assertEquals(previous, n.get("trace.id").asText(), "one trace for " + flow);
            }
            spansOfFlow.computeIfAbsent(flow, k -> new HashSet<>()).add(n.get("span.id").asText());
        }
        assertFalse(traceOfFlow.isEmpty());
        assertEquals(traceOfFlow.size(), new HashSet<>(traceOfFlow.values()).size(), "flows never share a trace");
        assertTrue(spansOfFlow.values().stream().anyMatch(s -> s.size() >= 2), "different stages have different spans");
        assertTrue(Files.exists(plain));
    }
}
