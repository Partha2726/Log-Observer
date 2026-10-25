package com.ielop.platform.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.elasticsearch.ElasticsearchContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Integration tests that need Elasticsearch: one container per JVM at the pinned
 * Elastic version (spec section 13), with the production index template and ILM
 * policy from {@code deploy/elasticsearch}, seeded once with the demo fixture.
 * Security is off in the test container; the backend's read-only user is
 * exercised by the stack smoke test instead.
 */
public abstract class ElasticsearchIntegrationTest extends IntegrationTest {

    public static final String ELASTIC_VERSION = "9.5.2";

    protected static final ElasticsearchContainer ELASTICSEARCH = new ElasticsearchContainer(
            DockerImageName.parse("docker.elastic.co/elasticsearch/elasticsearch:" + ELASTIC_VERSION))
            .withEnv("xpack.security.enabled", "false")
            .withEnv("xpack.ml.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m");

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final ObjectMapper JSON = new ObjectMapper();

    static {
        ELASTICSEARCH.start();
        installTemplates();
        index(EsFixtures.demoDocs());
    }

    @DynamicPropertySource
    static void elasticsearchProperties(DynamicPropertyRegistry registry) {
        registry.add("app.elasticsearch.url", () -> "http://" + ELASTICSEARCH.getHttpHostAddress());
        registry.add("app.elasticsearch.username", () -> "");
        registry.add("management.health.elasticsearch.enabled", () -> "true");
    }

    protected static String esUrl() {
        return "http://" + ELASTICSEARCH.getHttpHostAddress();
    }

    /** Bulk-indexes documents with their fixture IDs, then refreshes. */
    public static void index(List<EsFixtures.Doc> docs) {
        StringBuilder bulk = new StringBuilder();
        try {
            for (EsFixtures.Doc d : docs) {
                bulk.append("{\"create\":{\"_index\":\"").append(d.index()).append("\",\"_id\":\"").append(d.id()).append("\"}}\n");
                bulk.append(JSON.writeValueAsString(d.source())).append('\n');
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String body = send("POST", "/_bulk?refresh=true", bulk.toString(), "application/x-ndjson");
        if (body.contains("\"errors\":true") && !body.contains("version_conflict_engine_exception")) {
            throw new IllegalStateException("Bulk indexing failed: " + body.substring(0, Math.min(2000, body.length())));
        }
    }

    public static void deleteDocs(String index, List<String> ids) {
        for (String id : ids) {
            send("DELETE", "/" + index + "/_doc/" + id + "?refresh=true", null, null);
        }
    }

    private static void installTemplates() {
        Path dir = Path.of("..", "deploy", "elasticsearch");
        try {
            send("PUT", "/_ilm/policy/logs-flow-30d", Files.readString(dir.resolve("ilm-policy.json")), "application/json");
            send("PUT", "/_index_template/logs-flow", Files.readString(dir.resolve("index-template.json")), "application/json");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    protected static String send(String method, String path, String body, String contentType) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(esUrl() + path));
            if (body != null) {
                b.header("Content-Type", contentType).method(method, HttpRequest.BodyPublishers.ofString(body));
            } else {
                b.method(method, HttpRequest.BodyPublishers.noBody());
            }
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 300 && r.statusCode() != 404) {
                throw new IllegalStateException(method + " " + path + " -> " + r.statusCode() + ": " + r.body());
            }
            return r.body();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
