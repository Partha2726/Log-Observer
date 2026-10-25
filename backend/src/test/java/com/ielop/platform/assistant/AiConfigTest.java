package com.ielop.platform.assistant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Start-up validation of {@code app.ai.*} (T24: AI-013 and the provider allowlist of section 11.10). */
class AiConfigTest {

    private static AiProperties openai(String baseUrl, String key, String model) {
        AiProperties p = new AiProperties();
        p.setProvider("openai");
        p.setBaseUrl(baseUrl);
        p.setApiKey(key);
        p.setModel(model);
        return p;
    }

    @Test
    void fakeIsEnabledWithoutAnyProviderSettings() {
        AiAvailability a = AiConfig.availability(new AiProperties());
        assertThat(a.enabled()).isTrue();
        assertThat(a.provider()).isEqualTo("fake");
        assertThat(a.model()).isEqualTo(FakeAiGateway.MODEL);
    }

    @Test
    void openaiWithoutKeyOrModelIsDisabledNotBroken() {
        AiAvailability noKey = AiConfig.availability(openai("https://provider.example/v1", "", "model-x"));
        assertThat(noKey.enabled()).isFalse();
        assertThat(noKey.reason()).isEqualTo("AI_API_KEY is not set");
        assertThat(AiConfig.availability(openai("https://provider.example/v1", "k", " ")).reason()).isEqualTo("AI_MODEL is not set");
        assertThat(AiConfig.availability(openai("", "k", "model-x")).reason()).isEqualTo("AI_BASE_URL is not set");
        AiAvailability ok = AiConfig.availability(openai("https://provider.example/v1", "k", "model-x"));
        assertThat(ok.enabled()).isTrue();
        assertThat(ok.model()).isEqualTo("model-x");
    }

    @Test
    void killSwitchDisablesEveryProvider() {
        AiProperties p = openai("https://provider.example/v1", "k", "model-x");
        p.setEnabled(false);
        assertThat(AiConfig.availability(p).reason()).isEqualTo("AI_ENABLED=false");
        AiProperties fake = new AiProperties();
        fake.setEnabled(false);
        assertThat(AiConfig.availability(fake).enabled()).isFalse();
    }

    @Test
    void plainHttpIsOnlyAllowedForLocalHostsAndUnknownProvidersStopTheStart() {
        assertThat(AiConfig.availability(openai("http://localhost:8089/v1", "k", "m")).enabled()).isTrue();
        assertThat(AiConfig.availability(openai("http://127.0.0.1:8089/v1", "k", "m")).enabled()).isTrue();
        assertThatThrownBy(() -> AiConfig.availability(openai("http://provider.example/v1", "k", "m")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> AiConfig.availability(openai("ftp://provider.example", "k", "m"))).isInstanceOf(IllegalStateException.class);
        AiProperties other = new AiProperties();
        other.setProvider("anthropic");
        assertThatThrownBy(() -> AiConfig.availability(other)).hasMessageContaining("AI_PROVIDER");
    }

    @Test
    void propertiesNeverPrintTheKey() {
        assertThat(openai("https://provider.example/v1", "secret-value-123", "m").toString()).doesNotContain("secret-value-123").contains("<set>");
    }

    @Test
    void theChainSkipsProvidersWithoutAKeyAndFallbacksAloneEnableAi() {
        AiProperties p = openai("https://provider.example/v1", "", "model-x");
        p.getFallback1().setBaseUrl("https://api.groq.com/openai/v1");
        p.getFallback1().setApiKey("k1");
        p.getFallback1().setModel("fb-model");
        p.getFallback1().setMaxContextTokens(5000);
        p.getFallback2().setBaseUrl("https://integrate.api.nvidia.com/v1");
        p.getFallback2().setModel("fb2");
        AiAvailability a = AiConfig.availability(p);
        assertThat(a.enabled()).isTrue();
        assertThat(a.model()).isEqualTo("fb-model");
        assertThat(AiConfig.chain(p)).singleElement().satisfies(s -> {
            assertThat(s.name()).isEqualTo("groq");
            assertThat(s.maxContextTokens()).isEqualTo(5000);
        });
        p.setApiKey("k0");
        assertThat(AiConfig.chain(p)).extracting(OpenAiCompatibleGateway.Settings::name).containsExactly("provider", "groq");
        p.getFallback2().setBaseUrl("http://plain.example/v1");
        assertThatThrownBy(() -> AiConfig.availability(p)).hasMessageContaining("AI_FALLBACK2_BASE_URL must use HTTPS");
        assertThat(p.getFallback1().toString()).doesNotContain("k1");
    }
}
