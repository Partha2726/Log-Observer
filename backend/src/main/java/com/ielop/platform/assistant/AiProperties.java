package com.ielop.platform.assistant;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** {@code app.ai.*} (section 8.3). The model name always comes from configuration, never from code. */
@ConfigurationProperties(prefix = "app.ai")
public class AiProperties {

    /** Global kill switch: false makes every AI endpoint answer 503 {@code AI_DISABLED}. */
    private boolean enabled = true;
    /** {@code fake} (deterministic, offline) or {@code openai} (any OpenAI-compatible chat completions API). */
    private String provider = "fake";
    private String baseUrl = "";
    private String apiKey = "";
    private String model = "";
    private int maxContextTokens = 12_000;
    private int maxOutputTokens = 1_500;
    private int timeoutSeconds = 60;
    private int maxToolCallsPerTurn = 5;
    private long dailyTokenBudgetPerUser = 200_000;
    /** Client IPs inside these ranges are internal and kept; others are redacted (section 11.3). */
    private List<String> internalCidrs = List.of("10.0.0.0/8");
    /** Ordered fallback providers (OpenAI-compatible), tried when the one before is busy or failing. */
    private Provider fallback1 = new Provider();
    private Provider fallback2 = new Provider();

    /** One fallback provider; empty {@code apiKey} means "not configured" (skipped). */
    public static class Provider {
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "";
        private int maxContextTokens = 12_000;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String v) { baseUrl = v; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String v) { apiKey = v; }
        public String getModel() { return model; }
        public void setModel(String v) { model = v; }
        public int getMaxContextTokens() { return maxContextTokens; }
        public void setMaxContextTokens(int v) { maxContextTokens = v; }

        @Override
        public String toString() {
            return "Provider{baseUrl=" + baseUrl + ", model=" + model + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "<none>" : "<set>") + "}";
        }
    }

    /** Hosts allowed over plain HTTP (local providers and the WireMock test host); everything else needs HTTPS. */
    private List<String> plainHttpHosts = List.of("localhost", "127.0.0.1");

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public String getProvider() { return provider; }
    public void setProvider(String v) { provider = v; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { baseUrl = v; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { apiKey = v; }
    public String getModel() { return model; }
    public void setModel(String v) { model = v; }
    public int getMaxContextTokens() { return maxContextTokens; }
    public void setMaxContextTokens(int v) { maxContextTokens = v; }
    public int getMaxOutputTokens() { return maxOutputTokens; }
    public void setMaxOutputTokens(int v) { maxOutputTokens = v; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int v) { timeoutSeconds = v; }
    public int getMaxToolCallsPerTurn() { return maxToolCallsPerTurn; }
    public void setMaxToolCallsPerTurn(int v) { maxToolCallsPerTurn = v; }
    public long getDailyTokenBudgetPerUser() { return dailyTokenBudgetPerUser; }
    public void setDailyTokenBudgetPerUser(long v) { dailyTokenBudgetPerUser = v; }
    public List<String> getInternalCidrs() { return internalCidrs; }
    public void setInternalCidrs(List<String> v) { internalCidrs = v; }
    public List<String> getPlainHttpHosts() { return plainHttpHosts; }
    public Provider getFallback1() { return fallback1; }
    public void setFallback1(Provider v) { fallback1 = v; }
    public Provider getFallback2() { return fallback2; }
    public void setFallback2(Provider v) { fallback2 = v; }
    public void setPlainHttpHosts(List<String> v) { plainHttpHosts = v; }

    @Override
    public String toString() {
        // Never print the key.
        return "AiProperties{enabled=" + enabled + ", provider=" + provider + ", baseUrl=" + baseUrl + ", model=" + model
                + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "<none>" : "<set>") + "}";
    }
}
