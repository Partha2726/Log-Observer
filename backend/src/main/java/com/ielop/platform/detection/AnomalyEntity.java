package com.ielop.platform.detection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/** One stored anomaly (table {@code anomaly}, V3). {@code version} is the optimistic lock of reviews (section 10.2). */
@Entity
@Table(name = "anomaly")
public class AnomalyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "rule_id", nullable = false, length = 8)
    private String ruleId;
    @Column(name = "severity", nullable = false, length = 8)
    private String severity;
    @Column(name = "trace_id")
    private Long traceId;
    @Column(name = "user_id", length = 64)
    private String userId;
    @JdbcTypeCode(SqlTypes.INET)
    @Column(name = "client_ip", columnDefinition = "inet")
    private java.net.InetAddress clientIp;
    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;
    @Column(name = "window_start")
    private Instant windowStart;
    @Column(name = "window_end")
    private Instant windowEnd;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> details;
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "evidence_doc_ids", nullable = false, columnDefinition = "text[]")
    private String[] evidenceDocIds;
    @Column(name = "status", nullable = false, length = 12)
    private String status;
    @Column(name = "reviewed_by", length = 64)
    private String reviewedBy;
    @Column(name = "review_note", columnDefinition = "text")
    private String reviewNote;
    @Column(name = "reviewed_at")
    private Instant reviewedAt;
    @Version
    @Column(name = "version", nullable = false)
    private int version;
    @Column(name = "dedup_key", nullable = false, length = 128, unique = true)
    private String dedupKey;
    @Column(name = "flow_ref", length = 64)
    private String flowRef;

    public Long getId() { return id; }
    public String getRuleId() { return ruleId; }
    public String getSeverity() { return severity; }
    public Long getTraceId() { return traceId; }
    public String getUserId() { return userId; }
    public String getClientIp() { return clientIp == null ? null : clientIp.getHostAddress(); }
    public Instant getDetectedAt() { return detectedAt; }
    public Instant getWindowStart() { return windowStart; }
    public Instant getWindowEnd() { return windowEnd; }
    public Map<String, Object> getDetails() { return details; }
    public String[] getEvidenceDocIds() { return evidenceDocIds; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public String getReviewedBy() { return reviewedBy; }
    public void setReviewedBy(String v) { reviewedBy = v; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String v) { reviewNote = v; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant v) { reviewedAt = v; }
    public int getVersion() { return version; }
    public String getDedupKey() { return dedupKey; }
    public String getFlowRef() { return flowRef; }
}
