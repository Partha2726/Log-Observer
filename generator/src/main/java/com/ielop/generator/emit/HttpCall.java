package com.ielop.generator.emit;

import com.ielop.generator.model.Session;
import com.ielop.generator.threads.ThreadPools;

import java.util.SplittableRandom;

/**
 * One HTTP call through the gateway. The gateway creates the request ID
 * ({@code X-Request-Id}) and every service that handles the call logs it; the
 * workflow service serves it on one Tomcat thread and the billing service on
 * one worker thread. The cursor moves forward as the call's lines are written;
 * the gateway line is written last, at completion, with {@code rt} = elapsed.
 */
public final class HttpCall {

    final String rid;
    final long start;
    final String clientIp;
    final String userAgent;
    final Session session;
    final String workflowHost;
    final String httpThread;
    final String billingWorker;
    private long cursor;

    HttpCall(SplittableRandom rnd, long start, Session session, String clientIp, String userAgent) {
        this.rid = Ids.uuid(rnd);
        this.start = start;
        this.cursor = start;
        this.session = session;
        this.clientIp = clientIp;
        this.userAgent = userAgent;
        this.workflowHost = "wf-0" + (1 + rnd.nextInt(2));
        this.httpThread = ThreadPools.httpThread(rnd);
        this.billingWorker = ThreadPools.billingWorker(rnd);
    }

    /** Moves the cursor forward by a random step in {@code [minMs, maxMs]} and returns it. */
    long advance(SplittableRandom rnd, int minMs, int maxMs) {
        cursor += minMs + rnd.nextInt(maxMs - minMs + 1);
        return cursor;
    }

    long cursor() {
        return cursor;
    }

    double elapsedSeconds() {
        return (cursor - start) / 1000.0;
    }

    public String rid() {
        return rid;
    }
}
