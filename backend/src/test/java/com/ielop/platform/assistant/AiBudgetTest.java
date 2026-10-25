package com.ielop.platform.assistant;

import com.ielop.platform.common.exception.ApiException;
import com.ielop.platform.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Daily token budget (T24: AI-009): an over-budget user gets 429 before any provider call. */
class AiBudgetTest extends IntegrationTest {

    @Autowired
    private AiCalls calls;
    @Autowired
    private AiBudget budget;
    @Autowired
    private AiProperties props;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void usageAccumulatesAndOverBudgetFailsBeforeTheProviderIsCalled() {
        String user = "budget." + UUID.randomUUID();
        AtomicInteger providerCalls = new AtomicInteger();
        AiGateway.Usage usage = new AiGateway.Usage(1_000, 500);

        calls.call(user, "test", () -> {
            providerCalls.incrementAndGet();
            return usage;
        }, u -> u);
        assertThat(budget.used(user)).isEqualTo(1_500);

        jdbc.update("UPDATE ai_token_usage SET tokens = ? WHERE user_id = ?", props.getDailyTokenBudgetPerUser(), user);
        assertThatThrownBy(() -> calls.call(user, "test", () -> {
            providerCalls.incrementAndGet();
            return usage;
        }, u -> u)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo("AI_BUDGET_EXCEEDED");
            assertThat(e.status().value()).isEqualTo(429);
        });
        assertThat(providerCalls).hasValue(1);
    }

    @Test
    void aFailedCallRecordsNothingAndBudgetsAreKeptPerUser() {
        String a = "budget.a." + UUID.randomUUID();
        String b = "budget.b." + UUID.randomUUID();
        assertThatThrownBy(() -> calls.call(a, "test", () -> {
            throw new IllegalStateException("provider down");
        }, (AiGateway.Usage u) -> u)).isInstanceOf(IllegalStateException.class);
        assertThat(budget.used(a)).isZero();
        budget.record(b, 42);
        budget.record(b, 8);
        assertThat(budget.used(b)).isEqualTo(50);
        assertThat(budget.used(a)).isZero();
    }
}
