package com.ielop.platform.audit;

import com.ielop.platform.common.audit.AuditEventMessage;
import com.ielop.platform.common.audit.AuditPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stores each audit message as one {@code audit_event} row, replacing the logging
 * publisher. It joins the caller's transaction, so an action that rolls back leaves
 * no audit row; outside a transaction (a failed login) it writes its own.
 */
@Component
@Primary
public class JpaAuditPublisher implements AuditPublisher {

    private static final Logger log = LoggerFactory.getLogger(JpaAuditPublisher.class);
    static final String SYSTEM = "system";

    private final AuditEventRepository events;

    public JpaAuditPublisher(AuditEventRepository events) {
        this.events = events;
    }

    @Override
    @Transactional
    public void publish(AuditEventMessage e) {
        Map<String, Object> metadata = new LinkedHashMap<>(e.details() == null ? Map.of() : e.details());
        if (e.ipAddress() != null) {
            metadata.put("ip", e.ipAddress());
        }
        events.save(new AuditEventEntity(e.timestamp() == null ? Instant.now() : e.timestamp(), cut(e.principal() == null ? SYSTEM : e.principal(), 64),
                cut(e.action(), 64), cut(e.targetType(), 32), cut(e.targetId(), 64), cut(e.requestId(), 64), metadata.isEmpty() ? null : metadata));
        log.info("AUDIT: action='{}' actor='{}' target='{}/{}' reqId='{}'", e.action(), e.principal(), e.targetType(), e.targetId(), e.requestId());
    }

    private static String cut(String v, int max) {
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }
}
