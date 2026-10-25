package com.ielop.platform.trace.api;

import com.ielop.platform.common.exception.ApiException;
import com.ielop.platform.flowdef.FlowDefinitionRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Request validation shared by the API (section 8.7): path IDs, paging and time
 * ranges are checked before any query runs; problems become 400 {@code VALIDATION_ERROR}
 * with {@code fieldErrors}.
 */
@Component
public class ApiParams {

    public static final int DEFAULT_SIZE = 25;
    public static final int MAX_SIZE = 100;
    public static final Duration DEFAULT_RANGE = Duration.ofDays(7);
    public static final Duration MAX_RANGE = Duration.ofDays(31);
    static final Pattern UUID = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final FlowDefinitionRegistry flows;
    private final Pattern userPattern;
    private final Clock clock = Clock.systemUTC();

    public ApiParams(FlowDefinitionRegistry flows, @Value("${app.ids.user-pattern:^[A-Za-z0-9._@-]{1,64}$}") String userPattern) {
        this.flows = flows;
        this.userPattern = Pattern.compile(userPattern);
    }

    /** A time window; {@code from} and {@code to} are inclusive. */
    public record Window(Instant from, Instant to) {
    }

    /** Collects field errors and throws them together. */
    public final class Check {
        private final Map<String, String> errors = new LinkedHashMap<>();

        public String flowRef(String field, String value, boolean required) {
            if (value == null || value.isBlank()) {
                if (required) {
                    errors.put(field, "is required");
                }
                return null;
            }
            if (flows.forFlowRef(value).isEmpty()) {
                errors.put(field, "does not match the reference pattern of any loaded flow definition");
            }
            return value;
        }

        public String requestId(String field, String value) {
            if (value != null && !value.isBlank() && !UUID.matcher(value).matches()) {
                errors.put(field, "must be a UUID");
            }
            return value == null || value.isBlank() ? null : value;
        }

        public String userId(String field, String value, boolean required) {
            if (value == null || value.isBlank()) {
                if (required) {
                    errors.put(field, "is required");
                }
                return null;
            }
            if (!userPattern.matcher(value).matches()) {
                errors.put(field, "has an unsupported format");
            }
            return value;
        }

        public String oneOf(String field, String value, java.util.Collection<String> allowed) {
            if (value != null && !value.isBlank() && !allowed.contains(value)) {
                errors.put(field, "must be one of " + allowed);
            }
            return value == null || value.isBlank() ? null : value;
        }

        public String text(String field, String value, int max) {
            if (value != null && value.length() > max) {
                errors.put(field, "must be at most " + max + " characters");
            }
            return value == null || value.isBlank() ? null : value;
        }

        public int page(Integer value) {
            if (value != null && value < 0) {
                errors.put("page", "must be 0 or more");
                return 0;
            }
            return value == null ? 0 : value;
        }

        public int size(Integer value) {
            if (value != null && (value < 1 || value > MAX_SIZE)) {
                errors.put("size", "must be between 1 and " + MAX_SIZE);
                return DEFAULT_SIZE;
            }
            return value == null ? DEFAULT_SIZE : value;
        }

        /** Default: the last 7 days; at most 31 days. */
        public Window window(String from, String to) {
            Instant t = instant("to", to);
            Instant f = instant("from", from);
            if (t == null) {
                t = f != null ? f.plus(DEFAULT_RANGE) : clock.instant();
            }
            if (f == null) {
                f = t.minus(DEFAULT_RANGE);
            }
            if (f.isAfter(t)) {
                errors.put("from", "must not be after to");
            } else if (Duration.between(f, t).compareTo(MAX_RANGE) > 0) {
                errors.put("to", "the range may span at most 31 days");
            }
            return new Window(f, t);
        }

        /** An optional range without a default (Postgres-backed filters). */
        public Window optionalWindow(String from, String to) {
            Instant f = instant("from", from);
            Instant t = instant("to", to);
            if (f != null && t != null && f.isAfter(t)) {
                errors.put("from", "must not be after to");
            }
            return new Window(f, t);
        }

        private Instant instant(String field, String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (DateTimeParseException e) {
                try {
                    return Instant.parse(value);
                } catch (DateTimeParseException e2) {
                    errors.put(field, "must be an ISO-8601 date-time, for example 2026-05-15T13:10:02Z");
                    return null;
                }
            }
        }

        /** Records an error found by the caller's own check. */
        public void reject(String field, String message) {
            errors.putIfAbsent(field, message);
        }

        public void done() {
            if (!errors.isEmpty()) {
                throw ApiException.validation(errors);
            }
        }
    }

    public Check check() {
        return new Check();
    }
}
