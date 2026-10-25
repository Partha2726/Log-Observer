package com.ielop.platform.trace.assembly;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ielop.platform.logsearch.LogHit;
import com.ielop.platform.logsearch.LogHitMapper;
import com.ielop.platform.support.EsFixtures;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Hand-built hits for assembly unit tests, and the demo fixture as hits. */
public final class Hits {

    private static final AtomicLong OFFSETS = new AtomicLong();
    private static final ObjectMapper JSON = new ObjectMapper();

    private Hits() {
    }

    static Builder hit(String id, String ts) {
        return new Builder(id, Instant.parse(ts));
    }

    public static List<LogHit> demo() {
        List<LogHit> out = new ArrayList<>();
        for (EsFixtures.Doc d : EsFixtures.demoDocs()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> source = JSON.convertValue(d.source(), Map.class);
            out.add(LogHitMapper.map(d.id(), d.index(), source));
        }
        return out;
    }

    static final class Builder {
        private final String id;
        private final Instant ts;
        private String service = "svc";
        private String host = "h-01";
        private String thread;
        private String logger;
        private List<String> refs = List.of();
        private String user;
        private String session;
        private String rid;
        private String trace;
        private String span;
        private String action;
        private String method;
        private String path;
        private Integer status;
        private String errorType;
        private boolean error;
        private boolean warning;
        private String file;
        private final long offset = OFFSETS.incrementAndGet();
        private java.math.BigDecimal amount;

        Builder(String id, Instant ts) {
            this.id = id;
            this.ts = ts;
        }

        Builder service(String v) { service = v; return this; }
        Builder host(String v) { host = v; return this; }
        Builder thread(String v) { thread = v; return this; }
        Builder logger(String v) { logger = v; return this; }
        Builder ref(String... v) { refs = List.of(v); return this; }
        Builder user(String v) { user = v; return this; }
        Builder session(String v) { session = v; return this; }
        Builder rid(String v) { rid = v; return this; }
        Builder trace(String v) { trace = v; return this; }
        Builder span(String v) { span = v; return this; }
        Builder action(String v) { action = v; return this; }
        Builder http(String m, String p, int s) { method = m; path = p; status = s; error = s >= 500; warning = s >= 400 && s < 500; return this; }
        Builder error(String type) { errorType = type; error = true; return this; }
        Builder errorLevel() { error = true; return this; }
        Builder warn() { warning = true; return this; }
        Builder amount(String v) { amount = new java.math.BigDecimal(v); return this; }
        Builder file(String v) { file = v; return this; }

        LogHit build() {
            return new LogHit(id, "logs-flow-test", ts, service, host, null, error ? "ERROR" : "INFO", logger, thread, null, null,
                    refs, user, session, rid, action, null, method, path, status, null, null, errorType, null, error, warning,
                    false, file != null ? file : "/logs/" + service + "/" + host + ".log", offset,
                    Instant.parse("2026-05-16T00:00:00Z"), amount, null, amount != null ? "USD" : null, null, null,
                    null, null, null, null, List.of(), null, null, List.of(), trace, span);
        }
    }
}
