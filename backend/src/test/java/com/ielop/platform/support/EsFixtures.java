package com.ielop.platform.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * The Elasticsearch fixture built from a real generated run by
 * {@code scripts/make_backend_fixture.py}: parsed documents exactly as the
 * Logstash pipeline writes them, with their ground-truth labels.
 */
public final class EsFixtures {

    /** One fixture document. */
    public record Doc(String id, String index, JsonNode source, JsonNode truth) {
        public String text(String dottedPath) {
            JsonNode node = source;
            for (String part : dottedPath.split("\\.")) {
                node = node == null ? null : node.get(part);
            }
            return node == null || node.isNull() ? null : node.isArray() ? node.toString() : node.asText();
        }

        public List<String> list(String dottedPath) {
            JsonNode node = source;
            for (String part : dottedPath.split("\\.")) {
                node = node == null ? null : node.get(part);
            }
            List<String> out = new ArrayList<>();
            if (node != null && node.isArray()) {
                node.forEach(n -> out.add(n.asText()));
            } else if (node != null && !node.isNull()) {
                out.add(node.asText());
            }
            return out;
        }

        public String truthFlowRef() {
            return truth.get("flowRef").isNull() ? null : truth.get("flowRef").asText();
        }

        public String truthStage() {
            return truth.get("stage").isNull() ? null : truth.get("stage").asText();
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static List<Doc> demo;
    private static List<JsonNode> demoFlows;
    private static List<Doc> rules;

    private EsFixtures() {
    }

    /** The demo fixture, in {@code (@timestamp, log.file.path, log.offset)} order. */
    public static synchronized List<Doc> demoDocs() {
        if (demo == null) {
            demo = read("/fixtures/es/demo-docs.jsonl").stream()
                    .map(n -> new Doc(n.get("_id").asText(), n.get("_index").asText(), n.get("source"), n.get("truth")))
                    .toList();
        }
        return demo;
    }

    /** Ground truth of the demo fixture's flows ({@code flows.jsonl} rows). */
    public static synchronized List<JsonNode> demoFlows() {
        if (demoFlows == null) {
            demoFlows = read("/fixtures/es/demo-flows.jsonl");
        }
        return demoFlows;
    }

    /**
     * The rules fixture (RULE-020): flows of every scenario and the incidents, from a generated 1,000-flow
     * run on 2026-06-10, so its flowRefs never collide with the demo fixture's. Not indexed by default.
     */
    public static synchronized List<Doc> rulesDocs() {
        if (rules == null) {
            rules = read("/fixtures/es/rules-docs.jsonl.gz").stream()
                    .map(n -> new Doc(n.get("_id").asText(), n.get("_index").asText(), n.get("source"), n.get("truth")))
                    .toList();
        }
        return rules;
    }

    public static List<JsonNode> read(String resource) {
        try (InputStream raw = EsFixtures.class.getResourceAsStream(resource)) {
            if (raw == null) {
                throw new IllegalStateException("Missing test resource " + resource);
            }
            InputStream in = resource.endsWith(".gz") ? new GZIPInputStream(raw) : raw;
            List<JsonNode> rows = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank()) {
                    rows.add(JSON.readTree(line));
                }
            }
            return rows;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
