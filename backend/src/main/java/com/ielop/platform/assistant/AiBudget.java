package com.ielop.platform.assistant;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Per-user daily token budget (section 11.10): {@link #check} runs before every provider call and
 * throws 429 {@code AI_BUDGET_EXCEEDED} once the user's tokens for the UTC day reach
 * {@code daily-token-budget-per-user}; {@link #record} adds a call's tokens to {@code ai_token_usage}.
 */
@Service
public class AiBudget {

    private final JdbcTemplate jdbc;
    private final AiProperties props;
    private final Clock clock;

    @Autowired
    public AiBudget(JdbcTemplate jdbc, AiProperties props) {
        this(jdbc, props, Clock.systemUTC());
    }

    AiBudget(JdbcTemplate jdbc, AiProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    public long used(String userId) {
        Long tokens = jdbc.query("SELECT tokens FROM ai_token_usage WHERE user_id = ? AND day = ?",
                rs -> rs.next() ? rs.getLong(1) : 0L, userId, today());
        return tokens == null ? 0 : tokens;
    }

    public void check(String userId) {
        long used = used(userId);
        if (used >= props.getDailyTokenBudgetPerUser()) {
            throw AiException.budgetExceeded(used, props.getDailyTokenBudgetPerUser());
        }
    }

    /** Adds tokens in its own transaction, so usage counts even when the caller's work later fails. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String userId, long tokens) {
        if (tokens <= 0) {
            return;
        }
        jdbc.update("""
                INSERT INTO ai_token_usage (user_id, day, tokens) VALUES (?, ?, ?)
                ON CONFLICT (user_id, day) DO UPDATE SET tokens = ai_token_usage.tokens + EXCLUDED.tokens""", userId, today(), tokens);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }
}
