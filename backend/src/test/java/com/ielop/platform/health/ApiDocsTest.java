package com.ielop.platform.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ielop.platform.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code docs/API.md} is generated from the OpenAPI document at {@code /api/docs}
 * (section 8.7) and must match the running code. Regenerate with
 * {@code ./mvnw test -Dtest=ApiDocsTest -DupdateApiDocs=true}.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {"springdoc.api-docs.enabled=true", "springdoc.api-docs.path=/api/docs"})
class ApiDocsTest extends IntegrationTest {

    static final Path API_MD = Path.of("..", "docs", "API.md");

    @Autowired
    private MockMvc mvc;

    @Test
    void apiMarkdownMatchesTheOpenApiDocument() throws Exception {
        String raw = mvc.perform(get("/api/docs")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String markdown = render(new ObjectMapper().readTree(raw));
        if (Boolean.getBoolean("updateApiDocs")) {
            Files.writeString(API_MD, markdown, StandardCharsets.UTF_8);
        }
        assertThat(Files.exists(API_MD)).as("docs/API.md exists; run with -DupdateApiDocs=true").isTrue();
        assertThat(Files.readString(API_MD, StandardCharsets.UTF_8))
                .as("docs/API.md is stale; run ./mvnw test -Dtest=ApiDocsTest -DupdateApiDocs=true").isEqualTo(markdown);
    }

    static String render(JsonNode doc) {
        StringBuilder md = new StringBuilder();
        md.append("# Log Observer API\n\n");
        md.append("Generated from the OpenAPI document (`/api/docs`, dev profile) by `ApiDocsTest`; do not edit by hand.\n");
        md.append("The contracts are spec sections 7.6 and 8.7 to 8.8.\n\n");
        md.append("## Conventions\n\n");
        md.append("- Base path `/api`, JSON only. Every endpoint needs `Authorization: Bearer <JWT>` except `POST /api/auth/login`, ");
        md.append("`GET /api/health`, `GET /actuator/health` and `GET /actuator/info`.\n");
        md.append("- Times are ISO-8601 UTC with milliseconds, for example `2026-05-15T13:10:02.114Z`.\n");
        md.append("- Errors use one body: `{timestamp, status, error, message, path, code, requestId, fieldErrors}`. `requestId` equals the ");
        md.append("`X-Request-ID` response header. Codes: `VALIDATION_ERROR` 400, `UNAUTHORIZED` 401, `FORBIDDEN` 403, `NOT_FOUND` 404, ");
        md.append("`TRACE_NOT_FOUND` 404, `CONFLICT` 409, `RATE_LIMITED` 429, `INTERNAL_ERROR` 500, `ES_UNAVAILABLE` 503.\n");
        md.append("- Pages: `page` (0-based) and `size` (default 25, at most 100); body `{items, page, size, totalElements, totalPages, ");
        md.append("totalIsLowerBound}`. Elasticsearch-backed totals stop at 10,000 and then set `totalIsLowerBound`.\n");
        md.append("- Time ranges: `from` and `to`; Elasticsearch-backed lists default to the last 7 days and allow at most 31 days.\n");
        md.append("- Path IDs are validated before any query: a flowRef must match a loaded flow definition's `refPattern`, a request ID ");
        md.append("must be a UUID, and a user ID must match `app.ids.user-pattern`.\n");
        md.append("- When Elasticsearch is down, stored traces, graphs and Postgres-backed searches keep working; the rest answer 503 ");
        md.append("`ES_UNAVAILABLE`.\n\n");

        md.append("## Endpoints\n");
        Map<String, JsonNode> paths = sorted(doc.get("paths"));
        for (Map.Entry<String, JsonNode> p : paths.entrySet()) {
            for (Map.Entry<String, JsonNode> op : sorted(p.getValue()).entrySet()) {
                JsonNode o = op.getValue();
                md.append("\n### ").append(op.getKey().toUpperCase()).append(' ').append(p.getKey()).append("\n\n");
                if (o.hasNonNull("summary")) {
                    md.append(o.get("summary").asText()).append("\n\n");
                }
                if (o.has("parameters") && o.get("parameters").size() > 0) {
                    md.append("| Parameter | In | Required | Type |\n| --- | --- | --- | --- |\n");
                    for (JsonNode param : o.get("parameters")) {
                        md.append("| `").append(param.get("name").asText()).append("` | ").append(param.get("in").asText()).append(" | ")
                                .append(param.path("required").asBoolean(false) ? "yes" : "no").append(" | ")
                                .append(type(param.get("schema"))).append(" |\n");
                    }
                    md.append('\n');
                }
                if (o.has("requestBody")) {
                    md.append("Request body: ").append(type(o.at("/requestBody/content").elements().next().get("schema"))).append("\n\n");
                }
                for (Map.Entry<String, JsonNode> r : sorted(o.get("responses")).entrySet()) {
                    JsonNode content = r.getValue().get("content");
                    String body = content == null ? "" : " " + type(content.elements().next().get("schema"));
                    md.append("- `").append(r.getKey()).append("`").append(body).append('\n');
                }
            }
        }
        md.append("\n## Schemas\n");
        for (Map.Entry<String, JsonNode> s : sorted(doc.at("/components/schemas")).entrySet()) {
            md.append("\n### ").append(s.getKey()).append("\n\n");
            JsonNode props = s.getValue().get("properties");
            if (props == null) {
                md.append(type(s.getValue())).append("\n");
                continue;
            }
            md.append("| Field | Type |\n| --- | --- |\n");
            Iterator<Map.Entry<String, JsonNode>> it = props.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> f = it.next();
                md.append("| `").append(f.getKey()).append("` | ").append(type(f.getValue())).append(" |\n");
            }
        }
        return md.toString();
    }

    private static Map<String, JsonNode> sorted(JsonNode node) {
        Map<String, JsonNode> out = new TreeMap<>();
        if (node != null) {
            node.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue()));
        }
        return out;
    }

    private static String type(JsonNode schema) {
        if (schema == null) {
            return "";
        }
        if (schema.has("$ref")) {
            String ref = schema.get("$ref").asText();
            return ref.substring(ref.lastIndexOf('/') + 1);
        }
        String t = schema.path("type").asText("object");
        if ("array".equals(t)) {
            return "array of " + type(schema.get("items"));
        }
        if (schema.has("additionalProperties") && schema.get("additionalProperties").isObject()) {
            return "map of " + type(schema.get("additionalProperties"));
        }
        List<String> extra = new ArrayList<>();
        if (schema.has("format")) {
            extra.add(schema.get("format").asText());
        }
        return extra.isEmpty() ? t : t + " (" + String.join(", ", extra) + ")";
    }
}
