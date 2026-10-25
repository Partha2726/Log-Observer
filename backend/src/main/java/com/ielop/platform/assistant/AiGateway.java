package com.ielop.platform.assistant;

import java.util.List;
import java.util.function.Function;

/**
 * The AI provider boundary (section 11.2). Implementations: {@link FakeAiGateway} (deterministic,
 * offline) and {@link OpenAiCompatibleGateway} (Spring AI). Adding a provider means a new
 * implementation only. Outputs are untrusted text; callers validate them.
 */
public interface AiGateway {

    /** A structured trace summary; {@code validationErrors} are the previous attempt's problems on a retry. */
    record SummaryRequest(TraceContext context, String systemPrompt, String jsonSchema, List<String> validationErrors) {
    }

    record Usage(int inputTokens, int outputTokens) {
        public int total() {
            return inputTokens + outputTokens;
        }
    }

    /** {@code provider} and {@code model} name the provider that actually answered (it may be a fallback). */
    record SummaryResult(String rawText, Usage usage, String provider, String model) {
    }

    record ChatMessage(String role, String content) {
    }

    /** A read-only tool the model may call; {@code call} gets the JSON arguments and returns JSON text. */
    record Tool(String name, String description, String inputSchema, Function<String, String> call) {
    }

    /** {@code context} is the conversation's trace (already rendered into {@code systemPrompt}); null for a global conversation. */
    record ChatRequest(String systemPrompt, List<ChatMessage> history, String message, TraceContext context, List<Tool> tools) {
    }

    record ChatTurnResult(String answer, Usage usage, String provider, String model) {
    }

    String provider();

    String model();

    /** Every model this gateway may answer with (a fallback chain has several); summaries are cached per model. */
    default java.util.List<String> models() {
        return java.util.List.of(model());
    }

    SummaryResult summarize(SummaryRequest request);

    ChatTurnResult chat(ChatRequest request);
}
