package com.ielop.platform.logsearch;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Maps an Elasticsearch {@code _source} (nested ECS objects) to a {@link LogHit}. */
public final class LogHitMapper {

    private LogHitMapper() {
    }

    public static LogHit map(String id, String index, Map<String, Object> source) {
        return new LogHit(
                id,
                index,
                instant(get(source, "@timestamp")),
                str(source, "service.name"),
                str(source, "host.name"),
                str(source, "log_format"),
                str(source, "log.level"),
                str(source, "log.logger"),
                str(source, "process.thread.name"),
                str(source, "method"),
                integer(get(source, "line")),
                strings(get(source, "flow.refs")),
                str(source, "user.id"),
                str(source, "session.id"),
                str(source, "http.request.id"),
                str(source, "event.action"),
                str(source, "client.ip"),
                str(source, "http.request.method"),
                str(source, "url.path"),
                integer(get(source, "http.response.status_code")),
                decimalAsDouble(get(source, "event.duration_s")),
                str(source, "user_agent.original"),
                str(source, "error.type"),
                str(source, "flow.provider_status"),
                bool(get(source, "is_error")),
                bool(get(source, "is_warning")),
                bool(get(source, "has_stacktrace")),
                str(source, "log.file.path"),
                longValue(get(source, "log.offset")),
                instant(get(source, "ingested_at")),
                decimal(get(source, "flow.amount")),
                decimal(get(source, "flow.limit_amount")),
                str(source, "flow.currency"),
                str(source, "flow.counterparty_hash"),
                str(source, "flow.counterparty_last4"),
                str(source, "flow.reason"),
                str(source, "flow.permission"),
                str(source, "flow.resource"),
                str(source, "flow.mfa"),
                strings(get(source, "flow.external_correlation_id")),
                str(source, "message_body"),
                str(source, "message"),
                strings(get(source, "tags")),
                str(source, "trace.id"),
                str(source, "span.id"));
    }

    /** Reads a dotted path through nested objects, also accepting a flat dotted key. */
    @SuppressWarnings("unchecked")
    static Object get(Map<String, Object> source, String path) {
        if (source.containsKey(path)) {
            return source.get(path);
        }
        Object node = source;
        for (String part : path.split("\\.")) {
            if (!(node instanceof Map<?, ?> map)) {
                return null;
            }
            node = ((Map<String, Object>) map).get(part);
        }
        return node;
    }

    private static String str(Map<String, Object> source, String path) {
        Object v = get(source, path);
        return v == null ? null : v.toString();
    }

    private static Instant instant(Object v) {
        return v == null ? null : Instant.parse(v.toString());
    }

    private static Integer integer(Object v) {
        return v instanceof Number n ? Integer.valueOf(n.intValue()) : v == null ? null : Integer.valueOf(v.toString());
    }

    private static long longValue(Object v) {
        return v instanceof Number n ? n.longValue() : v == null ? 0L : Long.parseLong(v.toString());
    }

    private static boolean bool(Object v) {
        return v instanceof Boolean b ? b : v != null && Boolean.parseBoolean(v.toString());
    }

    private static BigDecimal decimal(Object v) {
        return v == null ? null : new BigDecimal(v.toString());
    }

    private static Double decimalAsDouble(Object v) {
        return v instanceof Number n ? Double.valueOf(n.doubleValue()) : v == null ? null : Double.valueOf(v.toString());
    }

    private static List<String> strings(Object v) {
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of(v.toString());
    }
}
