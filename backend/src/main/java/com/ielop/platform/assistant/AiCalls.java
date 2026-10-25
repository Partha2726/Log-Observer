package com.ielop.platform.assistant;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Every provider call goes through here: the budget is checked first (an over-budget user gets 429
 * before any provider call, AI-009), then the tokens used are recorded. Only the model, status,
 * token counts and latency are logged, never prompts or outputs (SEC-006).
 */
@Component
public class AiCalls {

    private static final Logger log = LoggerFactory.getLogger(AiCalls.class);

    private final AiGateway gateway;
    private final AiBudget budget;
    private final MeterRegistry meters;

    public AiCalls(AiGateway gateway, AiBudget budget, MeterRegistry meters) {
        this.gateway = gateway;
        this.budget = budget;
        this.meters = meters;
    }

    public AiGateway gateway() {
        return gateway;
    }

    public <R> R call(String userId, String operation, Supplier<R> call, Function<R, AiGateway.Usage> usage) {
        return call(userId, operation, call, usage, r -> gateway.provider() + "/" + gateway.model());
    }

    /** {@code who} names the provider and model that answered (a fallback chain may use a later one). */
    public <R> R call(String userId, String operation, Supplier<R> call, Function<R, AiGateway.Usage> usage, Function<R, String> who) {
        budget.check(userId);
        Timer.Sample sample = Timer.start(meters);
        String outcome = "error";
        long started = System.nanoTime();
        try {
            R result = call.get();
            AiGateway.Usage u = usage.apply(result);
            budget.record(userId, u.total());
            outcome = "ok";
            log.info("AI {} via {}: ok, {} input + {} output tokens, {} ms", operation, who.apply(result),
                    u.inputTokens(), u.outputTokens(), (System.nanoTime() - started) / 1_000_000);
            return result;
        } catch (RuntimeException e) {
            log.warn("AI {} via {}: failed ({}), {} ms", operation, gateway.provider() + (gateway.models().size() > 1 ? " chain" : ""), e.getClass().getSimpleName(),
                    (System.nanoTime() - started) / 1_000_000);
            throw e;
        } finally {
            sample.stop(meters.timer("ai.calls", "operation", operation, "provider", gateway.provider(), "outcome", outcome));
        }
    }

    public AiGateway.SummaryResult summarize(String userId, AiGateway.SummaryRequest request) {
        return call(userId, "summary", () -> gateway.summarize(request), AiGateway.SummaryResult::usage, r -> r.provider() + "/" + r.model());
    }

    public AiGateway.ChatTurnResult chat(String userId, AiGateway.ChatRequest request) {
        return call(userId, "chat", () -> gateway.chat(request), AiGateway.ChatTurnResult::usage, r -> r.provider() + "/" + r.model());
    }
}
