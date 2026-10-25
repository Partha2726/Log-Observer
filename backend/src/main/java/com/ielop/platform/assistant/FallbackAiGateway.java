package com.ielop.platform.assistant;

import com.ielop.platform.common.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * An ordered chain of OpenAI-compatible providers (for example Gemini, then Groq, then NVIDIA). Each provider
 * gets its own call with one retry on 429 or 5xx (inside {@link OpenAiCompatibleGateway}); any provider error
 * or timeout then moves on to the next. Providers without a key are not in the chain. A provider with a
 * smaller {@code maxContextTokens} gets a context shrunk to fit ({@link ContextFit}). When every provider
 * fails, the caller gets a short "AI provider busy" error; the provider's raw answer is never shown, and
 * only provider, model and status are logged.
 */
public class FallbackAiGateway implements AiGateway {

    private static final Logger log = LoggerFactory.getLogger(FallbackAiGateway.class);

    private final List<OpenAiCompatibleGateway> providers;
    private final int maxOutputTokens;

    public FallbackAiGateway(List<OpenAiCompatibleGateway> providers, int maxOutputTokens) {
        if (providers.isEmpty()) {
            throw new IllegalArgumentException("A fallback chain needs at least one provider");
        }
        this.providers = List.copyOf(providers);
        this.maxOutputTokens = maxOutputTokens;
    }

    public List<OpenAiCompatibleGateway> providers() {
        return providers;
    }

    @Override
    public String provider() {
        return providers.get(0).provider();
    }

    @Override
    public String model() {
        return providers.get(0).model();
    }

    @Override
    public List<String> models() {
        return providers.stream().map(OpenAiCompatibleGateway::model).distinct().toList();
    }

    @Override
    public SummaryResult summarize(SummaryRequest request) {
        return attempt("summary", g -> {
            int limit = g.settings().maxContextTokens();
            TraceContext fitted = ContextFit.fit(request.context(), request.systemPrompt().length() + lengthOf(request.jsonSchema())
                    + String.join("\n", request.validationErrors()).length(), limit, maxOutputTokens);
            return g.summarize(new SummaryRequest(fitted, request.systemPrompt(), request.jsonSchema(), request.validationErrors()));
        });
    }

    @Override
    public ChatTurnResult chat(ChatRequest request) {
        return attempt("chat", g -> g.chat(ContextFit.fit(request, g.settings().maxContextTokens(), maxOutputTokens)));
    }

    private <R> R attempt(String operation, Function<OpenAiCompatibleGateway, R> call) {
        List<String> failures = new ArrayList<>();
        for (OpenAiCompatibleGateway g : providers) {
            try {
                return call.apply(g);
            } catch (ApiException e) {
                failures.add(g.provider() + ": " + statusOf(e));
                log.warn("AI {} via {}/{} failed ({}); {}", operation, g.provider(), g.model(), statusOf(e),
                        g == providers.get(providers.size() - 1) ? "no provider left" : "trying the next provider");
            } catch (RuntimeException e) {
                failures.add(g.provider() + ": " + e.getClass().getSimpleName());
                log.warn("AI {} via {}/{} failed ({}); trying the next provider", operation, g.provider(), g.model(), e.getClass().getSimpleName());
            }
        }
        throw AiException.providerBusy(failures);
    }

    /** "HTTP 503", "SocketTimeoutException" and the like: never the provider's body or the prompt. */
    static String statusOf(ApiException e) {
        String m = String.valueOf(e.getMessage());
        int http = m.indexOf("HTTP ");
        if (http >= 0 && m.length() >= http + 8) {
            return m.substring(http, http + 8);
        }
        int colon = m.lastIndexOf(": ");
        return colon >= 0 ? m.substring(colon + 2, Math.min(m.length(), colon + 2 + 60)) : e.code();
    }

    private static int lengthOf(String s) {
        return s == null ? 0 : s.length();
    }
}
