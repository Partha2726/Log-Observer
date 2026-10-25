package com.ielop.generator.emit;

import com.ielop.generator.model.Service;
import org.apache.logging.log4j.Level;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One simulated log event, before it is written by Log4j2.
 *
 * <p>Events are created out of time order (one flow at a time) and sorted by
 * {@code (tsMillis, seq)} before emission; {@code seq} is the creation order,
 * so ties break deterministically.</p>
 *
 * <p>The truth fields ({@code flowRef}, {@code requestId}, {@code stage},
 * {@code noise}) are the ground-truth labels (spec section 5.6). They are the
 * real values even when the line itself does not print them, for example a
 * billing line whose {@code %X{requestId}} is empty.</p>
 */
public final class Event {

    final long tsMillis;
    final long seq;
    final Service service;
    final String host;
    final String thread;
    final Level level;
    final String logger;
    final String message;
    /** Format B fields (JSON keys) or format C structured-data parameters, in insertion order. */
    final Map<String, Object> fields;
    /** Format B {@code event.action} or format C {@code MSGID}. */
    final String action;
    /** Format D {@code %X{requestId}}; null writes an empty MDC value. */
    final String mdcRequestId;
    /** Format D location ({@code %M}, {@code %L}). */
    final StackTraceElement location;

    final String truthFlowRef;
    final String truthRequestId;
    final String truthStage;
    final boolean noise;

    private Event(Builder b, long seq) {
        this.tsMillis = b.tsMillis;
        this.seq = seq;
        this.service = b.service;
        this.host = b.host;
        this.thread = b.thread;
        this.level = b.level;
        this.logger = b.logger;
        this.message = b.message;
        this.fields = b.fields;
        this.action = b.action;
        this.mdcRequestId = b.mdcRequestId;
        this.location = b.location;
        this.truthFlowRef = b.truthFlowRef;
        this.truthRequestId = b.truthRequestId;
        this.truthStage = b.truthStage;
        this.noise = b.noise;
    }

    public long seq() {
        return seq;
    }

    public long tsMillis() {
        return tsMillis;
    }

    public Service service() {
        return service;
    }

    public String message() {
        return message;
    }

    public String action() {
        return action;
    }

    public String truthFlowRef() {
        return truthFlowRef;
    }

    public String truthRequestId() {
        return truthRequestId;
    }

    public String truthStage() {
        return truthStage;
    }

    public boolean noise() {
        return noise;
    }

    public Map<String, Object> fields() {
        return fields;
    }

    public String mdcRequestId() {
        return mdcRequestId;
    }

    public String thread() {
        return thread;
    }

    public String host() {
        return host;
    }

    public Level level() {
        return level;
    }

    public static Builder at(long tsMillis, Service service) {
        return new Builder(tsMillis, service);
    }

    public static final class Builder {
        private final long tsMillis;
        private final Service service;
        private String host;
        private String thread = "main";
        private Level level = Level.INFO;
        private String logger;
        private String message = "";
        private final Map<String, Object> fields = new LinkedHashMap<>();
        private String action;
        private String mdcRequestId;
        private StackTraceElement location;
        private String truthFlowRef;
        private String truthRequestId;
        private String truthStage;
        private boolean noise;

        private Builder(long tsMillis, Service service) {
            this.tsMillis = tsMillis;
            this.service = service;
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder thread(String thread) {
            this.thread = thread;
            return this;
        }

        public Builder level(Level level) {
            this.level = level;
            return this;
        }

        public Builder logger(String logger) {
            this.logger = logger;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        /** Adds a JSON key (format B) or structured-data parameter (format C); null values are skipped. */
        public Builder field(String key, Object value) {
            if (value != null) {
                fields.put(key, value);
            }
            return this;
        }

        public Builder action(String action) {
            this.action = action;
            return this;
        }

        public Builder mdcRequestId(String requestId) {
            this.mdcRequestId = requestId;
            return this;
        }

        public Builder location(String className, String method, int line) {
            String file = className.substring(className.lastIndexOf('.') + 1) + ".java";
            this.location = new StackTraceElement(className, method, file, line);
            return this;
        }

        public Builder truth(String flowRef, String requestId, String stage) {
            this.truthFlowRef = flowRef;
            this.truthRequestId = requestId;
            this.truthStage = stage;
            return this;
        }

        public Builder noise() {
            this.noise = true;
            return this;
        }

        public Event build(long seq) {
            return new Event(this, seq);
        }
    }
}
