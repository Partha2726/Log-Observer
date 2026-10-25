package com.ielop.generator.clock;

import org.apache.logging.log4j.core.util.Clock;

/**
 * Simulated clock, decoupled from wall time (spec section 5.2).
 *
 * <p>Log4j2 reads event timestamps from the clock named by the {@code log4j.Clock}
 * system property; {@code GeneratorMain} points it at this class before Log4j2
 * starts. The emitter moves the clock to each event's simulated time just before
 * logging it, so every layout (JSON, RFC 5424, pattern) writes the simulated time
 * with millisecond precision. Generation is single-threaded, so one static
 * value is enough.</p>
 */
public final class SimClock implements Clock {

    private static volatile long nowMillis;

    /** Public no-arg constructor: Log4j2 instantiates the clock reflectively. */
    public SimClock() {
    }

    public static void set(long epochMillis) {
        nowMillis = epochMillis;
    }

    public static long get() {
        return nowMillis;
    }

    @Override
    public long currentTimeMillis() {
        return nowMillis;
    }
}
