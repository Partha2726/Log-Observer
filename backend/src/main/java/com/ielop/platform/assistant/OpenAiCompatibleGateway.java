package com.ielop.platform.assistant;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Any OpenAI-compatible chat completions API through Spring AI (section 11.2): {@code AI_BASE_URL}
 * is the API root (for example {@code …/v1}), requests go to {@code <base>/chat/completions}.
 * Timeouts come from {@code app.ai.timeout-seconds}; a 429 or 5xx is retried once with backoff,
 * then the call fails with {@code AI_PROVIDER_ERROR}. The key is sent only as a bearer header and
 * never logged; prompts and outputs are never logged either (SEC-006).
 */
public class OpenAiCompatibleGateway implements AiGateway {

    /** One OpenAI-compatible endpoint: a short provider name (from the host), its URL, key, model and context limit. */
    public record Settings(String name, String baseUrl, String apiKey, String model, int maxContextTokens) {
        @Override
        public String toString() {
            return "Settings{" + name + ", " + model + "}";
        }
    }

    private final OpenAiChatModel chat;
    private final Settings settings;
    private final int maxOutputTokens;

    public OpenAiCompatibleGateway(AiProperties props) {
        this(new Settings(providerName(props.getBaseUrl()), props.getBaseUrl(), props.getApiKey(), props.getModel(), props.getMaxContextTokens()),
                props.getMaxOutputTokens(), props.getTimeoutSeconds());
    }

    public OpenAiCompatibleGateway(Settings settings, int maxOutputTokens, int timeoutSeconds) {
        this.settings = settings;
        this.maxOutputTokens = maxOutputTokens;
        SimpleClientHttpRequestFactory http = new SimpleClientHttpRequestFactory();
        http.setConnectTimeout(Duration.ofSeconds(Math.min(10, timeoutSeconds)));
        http.setReadTimeout(Duration.ofSeconds(timeoutSeconds));
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(stripSlash(settings.baseUrl()))
                .apiKey(settings.apiKey())
                .completionsPath("/chat/completions")
                .restClientBuilder(RestClient.builder().requestFactory(http).requestInterceptor(new ToolCallExtras()))
                .responseErrorHandler(new ProviderErrors())
                .build();
        this.chat = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model(settings.model()).maxTokens(maxOutputTokens).build())
                .retryTemplate(retryOnce())
                .build();
    }

    @Override
    public String provider() {
        return settings.name();
    }

    public Settings settings() {
        return settings;
    }

    /** A short provider name from the API host: generativelanguage.googleapis.com → googleapis, api.groq.com → groq. */
    static String providerName(String baseUrl) {
        try {
            String host = java.net.URI.create(baseUrl).getHost();
            if (host == null) {
                return "openai";
            }
            if (host.matches("[0-9.]+") || host.contains(":") || !host.contains(".")) {
                return host.length() > 32 ? host.substring(0, 32) : host;
            }
            String[] parts = host.split("\\.");
            String name = parts.length >= 2 ? parts[parts.length - 2] : host;
            return name.length() > 32 ? name.substring(0, 32) : name;
        } catch (IllegalArgumentException e) {
            return "openai";
        }
    }

    @Override
    public String model() {
        return settings.model();
    }

    @Override
    public SummaryResult summarize(SummaryRequest request) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(request.systemPrompt()));
        messages.add(new UserMessage(PromptText.context(request.context())));
        if (!request.validationErrors().isEmpty()) {
            messages.add(new UserMessage("Your previous answer was rejected for these reasons:\n- "
                    + String.join("\n- ", request.validationErrors()) + "\nAnswer again with JSON only, fixing every problem."));
        }
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().model(settings.model()).maxTokens(maxOutputTokens)
                .temperature(0.1);
        if (request.jsonSchema() != null) {
            options.responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_SCHEMA)
                    .jsonSchema(ResponseFormat.JsonSchema.builder().name("trace_summary").schema(request.jsonSchema()).strict(false).build())
                    .build());
        }
        ChatResponse response = call(new Prompt(messages, options.build()));
        return new SummaryResult(text(response), usage(response), settings.name(), settings.model());
    }

    @Override
    public ChatTurnResult chat(ChatRequest request) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(request.systemPrompt()));
        for (ChatMessage m : request.history()) {
            messages.add("assistant".equals(m.role()) ? new AssistantMessage(m.content()) : new UserMessage(m.content()));
        }
        messages.add(new UserMessage(request.message()));
        List<ToolCallback> tools = request.tools().stream().map(OpenAiCompatibleGateway::callback).toList();
        OpenAiChatOptions options = OpenAiChatOptions.builder().model(settings.model()).maxTokens(maxOutputTokens)
                .temperature(0.2).toolCallbacks(tools).internalToolExecutionEnabled(true).build();
        ChatResponse response = call(new Prompt(messages, options));
        return new ChatTurnResult(text(response), usage(response), settings.name(), settings.model());
    }

    private ChatResponse call(Prompt prompt) {
        try {
            return chat.call(prompt);
        } catch (RuntimeException e) {
            // Only the status line and error class leave this method; never the prompt or the response body.
            throw AiException.providerError("The AI provider call failed: " + describe(e), e);
        }
    }

    private static String describe(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String m = e instanceof TransientAiException || e instanceof NonTransientAiException ? e.getMessage() : root.getClass().getSimpleName();
        return m == null ? e.getClass().getSimpleName() : m.length() > 200 ? m.substring(0, 200) : m;
    }

    private static String text(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String t = response.getResult().getOutput().getText();
        return t == null ? "" : t;
    }

    private static AiGateway.Usage usage(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return new AiGateway.Usage(0, 0);
        }
        org.springframework.ai.chat.metadata.Usage u = response.getMetadata().getUsage();
        return u == null ? new AiGateway.Usage(0, 0)
                : new AiGateway.Usage(u.getPromptTokens() == null ? 0 : u.getPromptTokens(), u.getCompletionTokens() == null ? 0 : u.getCompletionTokens());
    }

    private static ToolCallback callback(Tool tool) {
        ToolDefinition def = ToolDefinition.builder().name(tool.name()).description(tool.description()).inputSchema(tool.inputSchema()).build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return def;
            }

            @Override
            public String call(String toolInput) {
                return tool.call().apply(toolInput);
            }
        };
    }

    /** One retry after 1 s for 429 and 5xx (mapped to {@link TransientAiException}); nothing else is retried. */
    static RetryTemplate retryOnce() {
        RetryTemplate t = new RetryTemplate();
        t.setRetryPolicy(new SimpleRetryPolicy(2, Map.of(TransientAiException.class, true), true));
        ExponentialBackOffPolicy backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(1_000);
        backoff.setMultiplier(2);
        backoff.setMaxInterval(4_000);
        t.setBackOffPolicy(backoff);
        return t;
    }

    /** 429 and 5xx are transient (retried once); other errors are not. Bodies are cut short and never logged. */
    static final class ProviderErrors implements ResponseErrorHandler {
        @Override
        public boolean hasError(ClientHttpResponse response) throws IOException {
            return response.getStatusCode().isError();
        }

        @Override
        public void handleError(URI url, org.springframework.http.HttpMethod method, ClientHttpResponse response) throws IOException {
            HttpStatusCode status = response.getStatusCode();
            String body = new String(response.getBody().readNBytes(300), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
            String message = "HTTP " + status.value() + " from the AI provider: " + body;
            if (status.value() == 429 || status.is5xxServerError()) {
                throw new TransientAiException(message);
            }
            throw new NonTransientAiException(message);
        }
    }

    private static String stripSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
