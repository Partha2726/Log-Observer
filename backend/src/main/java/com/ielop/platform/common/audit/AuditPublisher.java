package com.ielop.platform.common.audit;

/**
 * Contract for publishing audit event messages across module boundaries.
 *
 * <p>Owned by {@code common.audit}. Producer modules depend only on this
 * interface without importing the {@code audit} package's persistence.</p>
 */
public interface AuditPublisher {

    void publish(AuditEventMessage event);
}
