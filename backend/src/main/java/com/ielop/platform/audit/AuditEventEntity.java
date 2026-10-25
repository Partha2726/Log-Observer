package com.ielop.platform.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/** One {@code audit_event} row (V4). */
@Entity
@Table(name = "audit_event")
public class AuditEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
    @Column(name = "actor", nullable = false, length = 64)
    private String actor;
    @Column(name = "action", nullable = false, length = 64)
    private String action;
    @Column(name = "target_type", length = 32)
    private String targetType;
    @Column(name = "target_id", length = 64)
    private String targetId;
    @Column(name = "request_id", length = 64)
    private String requestId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    protected AuditEventEntity() {
    }

    public AuditEventEntity(Instant occurredAt, String actor, String action, String targetType, String targetId, String requestId,
                            Map<String, Object> metadata) {
        this.occurredAt = occurredAt;
        this.actor = actor;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.requestId = requestId;
        this.metadata = metadata;
    }

    public Long getId() { return id; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getTargetType() { return targetType; }
    public String getTargetId() { return targetId; }
    public String getRequestId() { return requestId; }
    public Map<String, Object> getMetadata() { return metadata; }
}
