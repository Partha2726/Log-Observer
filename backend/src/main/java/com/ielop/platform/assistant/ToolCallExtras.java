package com.ielop.platform.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps provider-specific data that an OpenAI-compatible API attaches to a tool call ({@code extra_content},
 * for example Gemini's {@code thought_signature}) and sends it back when that tool call is replayed in the next
 * request. Spring AI's OpenAI types drop unknown fields, and Gemini 3 rejects a replayed function call without
 * its signature (HTTP 400). Bodies are only rewritten in memory; nothing is logged.
 */
final class ToolCallExtras implements ClientHttpRequestInterceptor {

    private static final int MAX_ENTRIES = 1_000;

    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, JsonNode> extras = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, JsonNode> eldest) {
            return size() > MAX_ENTRIES;
        }
    });

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        ClientHttpResponse response = execution.execute(request, restore(body));
        if (!response.getStatusCode().is2xxSuccessful()) {
            return response;
        }
        byte[] bytes = response.getBody().readAllBytes();
        remember(bytes);
        return new Buffered(response, bytes);
    }

    /** Adds the remembered extra content to every replayed tool call of the outgoing request. */
    byte[] restore(byte[] body) {
        if (extras.isEmpty() || body.length == 0) {
            return body;
        }
        try {
            JsonNode root = json.readTree(body);
            boolean changed = false;
            for (JsonNode message : root.path("messages")) {
                for (JsonNode call : message.path("tool_calls")) {
                    JsonNode extra = extras.get(call.path("id").asText());
                    if (extra != null && call instanceof ObjectNode o && !o.has("extra_content")) {
                        o.set("extra_content", extra);
                        changed = true;
                    }
                }
            }
            return changed ? json.writeValueAsBytes(root) : body;
        } catch (IOException e) {
            return body;
        }
    }

    /** Remembers the extra content of every tool call in a response. */
    void remember(byte[] responseBody) {
        try {
            JsonNode root = json.readTree(responseBody);
            for (JsonNode choice : root.path("choices")) {
                for (JsonNode call : choice.path("message").path("tool_calls")) {
                    if (call.hasNonNull("id") && call.has("extra_content")) {
                        extras.put(call.get("id").asText(), call.get("extra_content"));
                    }
                }
            }
        } catch (IOException e) {
            // Not JSON (or not a chat completion): nothing to keep.
        }
    }

    private static final class Buffered implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final byte[] body;

        Buffered(ClientHttpResponse delegate, byte[] body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
