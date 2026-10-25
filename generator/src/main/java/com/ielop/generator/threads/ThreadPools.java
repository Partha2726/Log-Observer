package com.ielop.generator.threads;

import java.util.SplittableRandom;

/**
 * Named thread pools of the simulated services (spec section 5.2): Tomcat-style
 * request threads for the workflow service, a worker pool for the legacy billing
 * service, and scheduler threads for background jobs. Many flows share these
 * pools, which is what makes thread-based linking ambiguous.
 */
public final class ThreadPools {

    public static final int HTTP_THREADS = 24;
    public static final int BILLING_WORKERS = 8;
    public static final int SCHEDULERS = 2;

    private ThreadPools() {
    }

    public static String httpThread(SplittableRandom rnd) {
        return "http-nio-8080-exec-" + (1 + rnd.nextInt(HTTP_THREADS));
    }

    public static String billingWorker(SplittableRandom rnd) {
        return "billing-worker-" + (1 + rnd.nextInt(BILLING_WORKERS));
    }

    public static String scheduler(SplittableRandom rnd) {
        return "scheduling-" + (1 + rnd.nextInt(SCHEDULERS));
    }

    public static String cacheRefresh() {
        return "cache-refresh-1";
    }
}
