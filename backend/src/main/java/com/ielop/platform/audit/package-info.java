/**
 * The persistent audit trail (sections 8.6 and 11.1): {@link com.ielop.platform.audit.JpaAuditPublisher}
 * stores every {@code AuditEventMessage} as one {@code audit_event} row, and {@code GET /api/audit}
 * (ADMINISTRATOR only) reads them back.
 */
package com.ielop.platform.audit;
