package com.ielop.generator.emit;

import com.ielop.generator.model.Service;
import com.ielop.generator.model.Session;
import com.ielop.generator.model.User;
import com.ielop.generator.model.UserDirectory;
import com.ielop.generator.threads.ThreadPools;
import org.apache.logging.log4j.Level;

import java.time.Instant;
import java.time.ZoneId;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import java.util.function.LongUnaryOperator;

import static com.ielop.generator.emit.MessageCatalogue.*;

/**
 * Background activity that belongs to no flow (spec section 5.4): gateway health
 * checks and static assets, scheduler and cache-refresh jobs in the workflow and
 * billing services, auth token cleanup (all labelled noise), plus sign-in-only
 * sessions and occasional account events by employees (no flow, not noise).
 *
 * <p>Noise is generated one simulated minute at a time, each minute from its own
 * random stream, so the output does not depend on how the run is sliced.</p>
 */
public final class NoiseEmitter {

    /** Noise lines per flow-equivalent; tuned so noise is about 30% of all lines (section 5.4). */
    static final double NOISE_LINES_PER_FLOW = 33.0;
    /** Sign-in-only sessions per flow (section 5.8: sign-in-only sessions, probing and scanner traffic). */
    static final double BACKGROUND_SESSIONS_PER_FLOW = 0.3;
    /** Account events (password or role changes) per day. */
    static final double ACCOUNT_EVENTS_PER_DAY = 10.0;

    private final StageEmitter stages;
    private final UserDirectory users;
    private final Consumer<Event.Builder> out;
    private final LongUnaryOperator seedForMinute;
    private final ZoneId businessZone;
    private final double noisePerMinute;
    private final double sessionsPerMinute;
    private long nextMinute;

    public NoiseEmitter(StageEmitter stages, UserDirectory users, Consumer<Event.Builder> out,
                        LongUnaryOperator seedForMinute, ZoneId businessZone, long startMillis, double flowsPerDay) {
        this.stages = stages;
        this.users = users;
        this.out = out;
        this.seedForMinute = seedForMinute;
        this.businessZone = businessZone;
        this.noisePerMinute = flowsPerDay * NOISE_LINES_PER_FLOW / 1440.0;
        this.sessionsPerMinute = flowsPerDay * BACKGROUND_SESSIONS_PER_FLOW / 1440.0;
        this.nextMinute = Math.floorDiv(startMillis, 60_000L);
    }

    /** Generates every simulated minute that starts before {@code untilMillis}. */
    public void generateUntil(long untilMillis) {
        while (nextMinute * 60_000L < untilMillis) {
            minute(nextMinute);
            nextMinute++;
        }
    }

    private void minute(long minute) {
        SplittableRandom rnd = new SplittableRandom(seedForMinute.applyAsLong(minute));
        long t0 = minute * 60_000L;
        double activity = businessHours(t0) ? 1.0 : 0.25;

        // Load-balancer health checks every 15 s, day and night.
        for (int i = 0; i < 4; i++) {
            healthCheck(rnd, t0 + i * 15_000L + rnd.nextInt(500));
        }
        int lines = poisson(rnd, Math.max(0, noisePerMinute * activity - 4));
        for (int i = 0; i < lines; i++) {
            long t = t0 + rnd.nextInt(60_000);
            int kind = rnd.nextInt(100);
            if (kind < 15) {
                staticAsset(rnd, t);
            } else if (kind < 45) {
                workflowJob(rnd, t);
            } else if (kind < 90) {
                billingJob(rnd, t);
            } else {
                tokenCleanup(rnd, t);
            }
        }
        if (rnd.nextDouble() < sessionsPerMinute * activity) {
            backgroundSession(rnd, t0 + rnd.nextInt(60_000));
        }
        if (rnd.nextDouble() < ACCOUNT_EVENTS_PER_DAY / 1440.0) {
            accountEvent(rnd, t0 + rnd.nextInt(60_000));
        }
    }

    private void healthCheck(SplittableRandom rnd, long t) {
        String rid = Ids.uuid(rnd);
        out.accept(stages.catalogue().gateway(t, stages.catalogue().accessLine("10.0.0." + (5 + rnd.nextInt(2)), null, t,
                "GET", "/health", 200, 15, null, "acme-lb-healthcheck/1.0", rid, 0.001 + rnd.nextInt(3) / 1000.0))
                .truth(null, rid, null).noise());
    }

    private void staticAsset(SplittableRandom rnd, long t) {
        String rid = Ids.uuid(rnd);
        User u = users.randomUser(rnd);
        String asset = switch (rnd.nextInt(4)) {
            case 0 -> "/static/app.3f9a1c.js";
            case 1 -> "/static/app.8be210.css";
            case 2 -> "/static/vendor.c41d07.js";
            default -> "/favicon.ico";
        };
        out.accept(stages.catalogue().gateway(t, stages.catalogue().accessLine(u.homeIp(), null, t, "GET", asset, 200,
                1_000 + rnd.nextInt(90_000), REFERRER, u.userAgent(), rid, 0.002 + rnd.nextInt(20) / 1000.0))
                .truth(null, rid, null).noise());
    }

    private void workflowJob(SplittableRandom rnd, long t) {
        String host = "wf-0" + (1 + rnd.nextInt(2));
        Event.Builder b = switch (rnd.nextInt(4)) {
            case 0 -> workflow(t, host, ThreadPools.scheduler(rnd), WF_SYNC_JOB, Level.INFO, null, "Budget sync started");
            case 1 -> workflow(t, host, ThreadPools.scheduler(rnd), WF_SYNC_JOB, Level.INFO, null,
                    "Budget sync completed in " + (40 + rnd.nextInt(900)) + " ms (" + (10 + rnd.nextInt(40)) + " cost centres)");
            case 2 -> workflow(t, host, ThreadPools.scheduler(rnd), WF_REMINDER_JOB, Level.DEBUG, null,
                    "Sent " + rnd.nextInt(12) + " approval reminders");
            default -> workflow(t, host, ThreadPools.cacheRefresh(), WF_VENDOR_CACHE, Level.INFO, null,
                    "Vendor cache refreshed (" + (200 + rnd.nextInt(800)) + " entries)");
        };
        out.accept(b.truth(null, null, null).noise());
    }

    private void billingJob(SplittableRandom rnd, long t) {
        Event.Builder b = switch (rnd.nextInt(4)) {
            case 0 -> billing(t, ThreadPools.scheduler(rnd), BL_SYNC_JOB, "runBatch", 72, Level.INFO,
                    "Budget sync batch " + (1 + rnd.nextInt(8)) + " of 8"
                            + (rnd.nextDouble() < StageEmitter.SQL_RATE ? "\n" + Multiline.sql(rnd) : ""));
            case 1 -> billing(t, ThreadPools.scheduler(rnd), BL_POOL, "stats", -1, Level.DEBUG,
                    "Pool stats active=" + rnd.nextInt(6) + " idle=" + (4 + rnd.nextInt(6)) + " waiting=0");
            case 2 -> billing(t, ThreadPools.scheduler(rnd), BL_SYNC_JOB, "heartbeat", 31, Level.DEBUG,
                    "Health check OK");
            default -> billing(t, ThreadPools.cacheRefresh(), BL_RATE_CACHE, "refresh", 55, Level.INFO,
                    "Exchange rate cache refreshed (" + (8 + rnd.nextInt(20)) + " currencies)");
        };
        out.accept(b.truth(null, null, null).noise());
    }

    private void tokenCleanup(SplittableRandom rnd, long t) {
        out.accept(Event.at(t, Service.AUTH).host(AUTH_HOST).thread("main").logger(AUTH_CLEANUP).level(Level.INFO)
                .message("Token cleanup removed " + rnd.nextInt(40) + " expired tokens")
                .truth(null, null, null).noise());
    }

    /** An employee signs in, lists requests a few times, and signs out (or lets the session expire). */
    private void backgroundSession(SplittableRandom rnd, long t) {
        StageEmitter.Ctx c = stages.ctx(null, rnd);
        User user = users.randomUser(rnd);
        Session s = stages.signIn(c, user, t, null);
        long cursor = s.lastActivityMillis();
        int views = 1 + rnd.nextInt(4);
        for (int i = 0; i < views; i++) {
            cursor += StageEmitter.between(rnd, 3_000, 90_000);
            HttpCall call = new HttpCall(rnd, cursor, s, s.ip(), user.userAgent());
            boolean pending = rnd.nextBoolean();
            stages.emit(c, null, call, stages.wfNoRef(call, call.advance(rnd, 5, 40), WF_QUERY, Level.INFO, null,
                    "Listed " + rnd.nextInt(30) + (pending ? " pending requests" : " requests")));
            cursor = stages.gatewayEnd(c, null, call, "GET", pending ? "/api/requests?status=pending" : "/api/requests/mine",
                    200, 900 + rnd.nextInt(4_000));
        }
        if (rnd.nextInt(10) == 0) {
            stages.expire(c, s);
        } else {
            stages.signOut(c, s, cursor + StageEmitter.between(rnd, 5_000, 120_000));
        }
    }

    private void accountEvent(SplittableRandom rnd, long t) {
        User user = users.randomUser(rnd);
        String rid = Ids.uuid(rnd);
        boolean password = rnd.nextBoolean();
        Event.Builder b = password
                ? auth(t, AUTH_ACCOUNT, Level.INFO, PASSWORD_CHANGED, "Password changed")
                : auth(t, AUTH_ACCOUNT, Level.WARN, ROLE_CHANGED, "Role changed");
        b.field("user", user.id()).field("rid", rid).field("ip", user.homeIp());
        if (!password) {
            b.field("perm", "requests:approve");
        }
        out.accept(b.truth(null, rid, null));
    }

    private boolean businessHours(long millis) {
        int hour = Instant.ofEpochMilli(millis).atZone(businessZone).getHour();
        return hour >= 8 && hour < 20;
    }

    /** Knuth's method; fine for the small means used here. */
    static int poisson(SplittableRandom rnd, double mean) {
        if (mean <= 0) {
            return 0;
        }
        double limit = Math.exp(-mean);
        double p = rnd.nextDouble();
        int k = 0;
        while (p > limit) {
            p *= rnd.nextDouble();
            k++;
        }
        return k;
    }
}
