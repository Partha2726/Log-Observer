package com.ielop.platform.common.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * Default fallback implementation of {@link AuditPublisher} that logs audit events.
 *
 * <p>Replaced automatically when the {@code audit} package registers the persistent {@code AuditPublisher} bean.</p>
 */
@Component
@ConditionalOnMissingBean(type = "com.ielop.platform.audit.AuditService")
public class LoggingAuditPublisher implements AuditPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingAuditPublisher.class);

    @Override
    public void publish(AuditEventMessage event) {
        log.info("AUDIT: action='{}' principal='{}' target='{}/{}' reqId='{}'",
                event.action(), event.principal(), event.targetType(), event.targetId(), event.requestId());
    }
}
