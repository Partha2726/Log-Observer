package com.ielop.platform.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the {@link AiGateway} from {@code app.ai.*} at start-up (sections 8.3 and 11.10). An unknown
 * provider or a non-HTTPS base URL (other than a local host) stops the start; a missing key or model,
 * or {@code AI_ENABLED=false}, starts the application with AI {@code DISABLED}.
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

    @Bean
    public AiAvailability aiAvailability(AiProperties p) {
        AiAvailability a = availability(p);
        if (a.enabled()) {
            log.info("AI enabled: provider {}, model {}", a.provider(), a.model());
        } else {
            log.warn("AI disabled: {}", a.reason());
        }
        return a;
    }

    @Bean
    public AiGateway aiGateway(AiProperties p, AiAvailability a, ObjectMapper json) {
        if (!a.enabled()) {
            return new DisabledAiGateway(a);
        }
        if ("fake".equals(a.provider())) {
            return new FakeAiGateway(json);
        }
        List<OpenAiCompatibleGateway> chain = chain(p).stream()
                .map(s -> new OpenAiCompatibleGateway(s, p.getMaxOutputTokens(), p.getTimeoutSeconds())).toList();
        log.info("AI provider chain: {}", chain.stream().map(g -> g.provider() + "/" + g.model()).toList());
        return new FallbackAiGateway(chain, p.getMaxOutputTokens());
    }

    /**
     * The configured OpenAI-compatible providers in order: primary ({@code AI_*}), then {@code AI_FALLBACK1_*} and
     * {@code AI_FALLBACK2_*}. A provider without base URL, key or model is skipped.
     */
    static List<OpenAiCompatibleGateway.Settings> chain(AiProperties p) {
        List<OpenAiCompatibleGateway.Settings> out = new ArrayList<>();
        add(out, p.getBaseUrl(), p.getApiKey(), p.getModel(), p.getMaxContextTokens());
        add(out, p.getFallback1().getBaseUrl(), p.getFallback1().getApiKey(), p.getFallback1().getModel(), p.getFallback1().getMaxContextTokens());
        add(out, p.getFallback2().getBaseUrl(), p.getFallback2().getApiKey(), p.getFallback2().getModel(), p.getFallback2().getMaxContextTokens());
        return out;
    }

    private static void add(List<OpenAiCompatibleGateway.Settings> out, String url, String key, String model, int maxContext) {
        if (!blankToEmpty(url).isEmpty() && !blankToEmpty(key).isEmpty() && !blankToEmpty(model).isEmpty()) {
            out.add(new OpenAiCompatibleGateway.Settings(OpenAiCompatibleGateway.providerName(url.trim()), url.trim(), key.trim(), model.trim(), maxContext));
        }
    }

    /** Validates the configuration; throws {@link IllegalStateException} for settings that must stop the start. */
    static AiAvailability availability(AiProperties p) {
        String provider = p.getProvider() == null ? "" : p.getProvider().trim().toLowerCase(Locale.ROOT);
        if (!provider.equals("fake") && !provider.equals("openai")) {
            throw new IllegalStateException("AI_PROVIDER must be 'fake' or 'openai', not '" + p.getProvider() + "'");
        }
        String model = provider.equals("fake") ? FakeAiGateway.MODEL : blankToEmpty(p.getModel());
        if (!p.isEnabled()) {
            return AiAvailability.disabled(provider, model, "AI_ENABLED=false");
        }
        if (provider.equals("fake")) {
            return new AiAvailability(true, provider, model, null);
        }
        checkUrl("AI_BASE_URL", p.getBaseUrl(), p);
        checkUrl("AI_FALLBACK1_BASE_URL", p.getFallback1().getBaseUrl(), p);
        checkUrl("AI_FALLBACK2_BASE_URL", p.getFallback2().getBaseUrl(), p);
        List<OpenAiCompatibleGateway.Settings> chain = chain(p);
        if (!chain.isEmpty()) {
            return new AiAvailability(true, provider, chain.get(0).model(), null);
        }
        String base = blankToEmpty(p.getBaseUrl());
        if (base.isEmpty()) {
            return AiAvailability.disabled(provider, model, "AI_BASE_URL is not set");
        }
        if (blankToEmpty(p.getApiKey()).isEmpty()) {
            return AiAvailability.disabled(provider, model, "AI_API_KEY is not set");
        }
        return AiAvailability.disabled(provider, model, "AI_MODEL is not set");
    }

    /** A set URL must be valid and HTTPS (plain HTTP only for local hosts); an unset one is skipped. */
    private static void checkUrl(String name, String value, AiProperties p) {
        String base = blankToEmpty(value);
        if (base.isEmpty()) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(base);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(name + " is not a valid URL", e);
        }
        boolean https = "https".equalsIgnoreCase(uri.getScheme());
        boolean localHttp = "http".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
                && p.getPlainHttpHosts().stream().anyMatch(h -> h.equalsIgnoreCase(uri.getHost()));
        if (uri.getHost() == null || !(https || localHttp)) {
            throw new IllegalStateException(name + " must use HTTPS (plain HTTP is allowed only for " + p.getPlainHttpHosts() + ")");
        }
    }

    private static String blankToEmpty(String v) {
        return v == null ? "" : v.trim();
    }
}
