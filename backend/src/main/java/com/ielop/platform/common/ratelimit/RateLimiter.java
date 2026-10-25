package com.ielop.platform.common.ratelimit;

import com.ielop.platform.common.exception.ApiException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process sliding-window rate limits (section 8.10): at most {@code limit} events per key within
 * {@code window}. One backend instance, so in-process state is enough; limits reset on restart.
 */
@Component
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimiter {

    private final Map<String, Deque<Long>> events = new ConcurrentHashMap<>();
    private final Clock clock;

    public RateLimiter() {
        this(Clock.systemUTC());
    }

    RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** Counts one event for {@code key}, or throws 429 {@code RATE_LIMITED} if the window is full. */
    public void acquire(String key, int limit, Duration window, String what) {
        long now = clock.millis();
        Deque<Long> q = events.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst() <= now - window.toMillis()) {
                q.pollFirst();
            }
            if (q.size() >= limit) {
                long retry = Math.max(1, (q.peekFirst() + window.toMillis() - now + 999) / 1000);
                throw new ApiException("RATE_LIMITED", HttpStatus.TOO_MANY_REQUESTS,
                        "Too many " + what + "; at most " + limit + " per " + describe(window) + ". Try again in " + retry + " s");
            }
            q.addLast(now);
        }
    }

    private static String describe(Duration d) {
        return d.toHours() >= 1 ? d.toHours() + " h" : d.toMinutes() + " min";
    }
}
