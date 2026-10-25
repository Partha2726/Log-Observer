package com.ielop.generator.emit;

import com.ielop.generator.clock.SimClock;
import com.ielop.generator.model.Service;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.context.Scope;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.message.MapMessage;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.message.StructuredDataMessage;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Writes events through the Log4j2 appenders configured in {@code log4j2-generator.xml}.
 *
 * <p>For each event it moves the simulated clock, renames the current thread to
 * the event's thread (the layouts read {@code %t} and {@code thread.name} from
 * it), sets the context keys the appenders route on, and logs with the message
 * type each layout expects. It also counts lines per file so ground truth can
 * point at the exact first line of every event.</p>
 */
public final class Emitter {

    /** Process IDs written in the syslog header, fixed per host for determinism. */
    private static final Map<String, String> SYSLOG_PROC_IDS = Map.of("auth-01", "2211");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ZoneId legacyZone;
    private final LineSink sink;
    /** Phase 3 OpenTelemetry mode; null when off (the default), and then nothing below changes the output. */
    private final OtelTraces otel;
    private final Map<String, Integer> nextLine = new HashMap<>();
    private final Map<String, Logger> loggers = new HashMap<>();
    private final Logger gatewayLogger = LogManager.getLogger("acme.gateway.access");
    private long count;

    public Emitter(ZoneId legacyZone, LineSink sink) {
        this(legacyZone, sink, null);
    }

    public Emitter(ZoneId legacyZone, LineSink sink, OtelTraces otel) {
        this.legacyZone = legacyZone;
        this.sink = sink;
        this.otel = otel;
    }

    public long count() {
        return count;
    }

    public void emit(Event e) {
        String file = fileFor(e);
        int lineNo = nextLine.getOrDefault(file, 1);

        SimClock.set(e.tsMillis);
        Thread.currentThread().setName(e.thread);
        ThreadContext.put("file", file);
        // OpenTelemetry mode: lines of a flow run inside that flow's stage span, so the context-data provider puts
        // trace_id and span_id into the log event's context data (formats B and D read them from there).
        SpanContext span = otel != null && e.truthFlowRef != null ? otel.spanFor(e.truthFlowRef, e.truthStage, e.tsMillis) : null;
        try (Scope ignored = span != null ? Span.wrap(span).makeCurrent() : Scope.noop()) {
            write(e, span);
        }
        ThreadContext.clearMap();

        nextLine.put(file, lineNo + lineCount(e));
        sink.accept(e, file, lineNo);
        count++;
    }

    private void write(Event e, SpanContext span) {
        switch (e.service) {
            case GATEWAY -> gatewayLogger.log(e.level, otel == null ? e.message
                    : e.message + " tid=" + (span != null ? span.getTraceId() : "-"));
            case WORKFLOW -> logger(e.logger).log(e.level, jsonMessage(e));
            case AUTH -> {
                ThreadContext.put("host", e.host);
                ThreadContext.put("procid", SYSLOG_PROC_IDS.getOrDefault(e.host, "-"));
                logger(e.logger).log(e.level, syslogMessage(e, span));
            }
            case BILLING -> {
                if (e.mdcRequestId != null) {
                    ThreadContext.put("requestId", e.mdcRequestId);
                } else {
                    ThreadContext.remove("requestId");
                }
                logger(e.logger).atLevel(e.level).withLocation(e.location).log(e.message);
            }
        }
    }

    /** Path relative to the output root, e.g. {@code billing-service/billing-bill-01-2026-05-15.log}. */
    String fileFor(Event e) {
        Instant ts = Instant.ofEpochMilli(e.tsMillis);
        return switch (e.service) {
            case GATEWAY -> "gateway/access-" + e.host + "-" + DAY.format(ts.atZone(legacyZone)) + ".log";
            case WORKFLOW -> "workflow-service/app-" + DAY.format(ts.atZone(ZoneOffset.UTC)) + ".json";
            case AUTH -> "auth-service/auth-" + DAY.format(ts.atZone(ZoneOffset.UTC)) + ".log";
            case BILLING -> "billing-service/billing-" + e.host + "-" + DAY.format(ts.atZone(legacyZone)) + ".log";
        };
    }

    /** Only format D writes multi-line events; JSON and syslog messages are single lines. */
    private static int lineCount(Event e) {
        if (e.service != Service.BILLING) {
            return 1;
        }
        int lines = 1;
        for (int i = 0; i < e.message.length(); i++) {
            if (e.message.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private Logger logger(String name) {
        return loggers.computeIfAbsent(name, LogManager::getLogger);
    }

    private static MapMessage<?, Object> jsonMessage(Event e) {
        MapMessage<?, Object> m = new MapMessage<>();
        m.with("host.name", e.host);
        e.fields.forEach(m::with);
        if (e.action != null) {
            m.with("event.action", e.action);
        }
        m.with("message", e.message);
        return m;
    }

    private static Message syslogMessage(Event e, SpanContext span) {
        if (e.action == null && e.fields.isEmpty() && span == null) {
            return new SimpleMessage(e.message);
        }
        StructuredDataMessage m = new StructuredDataMessage("acme", e.message, e.action);
        e.fields.forEach((k, v) -> m.put(k, String.valueOf(v)));
        if (span != null) {
            m.put("trace", span.getTraceId());
            m.put("span", span.getSpanId());
        }
        return m;
    }
}
