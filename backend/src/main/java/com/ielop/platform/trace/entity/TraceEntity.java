package com.ielop.platform.trace.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

/** One assembled flow (table {@code trace}, migration V2). Derived data, rebuildable from Elasticsearch. */
@Entity
@Table(name = "trace")
public class TraceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "flow_ref", nullable = false, length = 64, unique = true)
    private String flowRef;
    @Column(name = "flow_id", nullable = false, length = 64)
    private String flowId;
    @Column(name = "flow_version", nullable = false)
    private int flowVersion;
    @Column(name = "requester_user_id", length = 64)
    private String requesterUserId;
    @Column(name = "approver_user_id", length = 64)
    private String approverUserId;
    @Column(name = "executor_user_id", length = 64)
    private String executorUserId;
    @Column(name = "overall_status", nullable = false, length = 16)
    private String overallStatus;
    @Column(name = "first_failure_stage", length = 40)
    private String firstFailureStage;
    @Column(name = "first_failure_component", length = 255)
    private String firstFailureComponent;
    @Column(name = "first_failure_exception", length = 255)
    private String firstFailureException;
    @Column(name = "first_failure_doc_id", length = 64)
    private String firstFailureDocId;
    @Column(name = "first_failure_http_status")
    private Integer firstFailureHttpStatus;
    @Column(name = "first_seen", nullable = false)
    private Instant firstSeen;
    @Column(name = "last_seen", nullable = false)
    private Instant lastSeen;
    @Column(name = "amount", precision = 15, scale = 2)
    private BigDecimal amount;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", length = 3)
    private String currency;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "counterparty_hash", length = 64)
    private String counterpartyHash;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "counterparty_last4", length = 4)
    private String counterpartyLast4;
    @Column(name = "observed_ratio", precision = 3, scale = 2)
    private BigDecimal observedRatio;
    @Column(name = "excluded_count", nullable = false)
    private int excludedCount;
    @Column(name = "truncated", nullable = false)
    private boolean truncated;
    @Column(name = "assembly_version", nullable = false)
    private int assemblyVersion;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "config_hash", nullable = false, length = 64)
    private String configHash;
    @Column(name = "assembled_at", nullable = false)
    private Instant assembledAt;

    public Long getId() { return id; }
    public String getFlowRef() { return flowRef; }
    public void setFlowRef(String v) { flowRef = v; }
    public String getFlowId() { return flowId; }
    public void setFlowId(String v) { flowId = v; }
    public int getFlowVersion() { return flowVersion; }
    public void setFlowVersion(int v) { flowVersion = v; }
    public String getRequesterUserId() { return requesterUserId; }
    public void setRequesterUserId(String v) { requesterUserId = v; }
    public String getApproverUserId() { return approverUserId; }
    public void setApproverUserId(String v) { approverUserId = v; }
    public String getExecutorUserId() { return executorUserId; }
    public void setExecutorUserId(String v) { executorUserId = v; }
    public String getOverallStatus() { return overallStatus; }
    public void setOverallStatus(String v) { overallStatus = v; }
    public String getFirstFailureStage() { return firstFailureStage; }
    public void setFirstFailureStage(String v) { firstFailureStage = v; }
    public String getFirstFailureComponent() { return firstFailureComponent; }
    public void setFirstFailureComponent(String v) { firstFailureComponent = v; }
    public String getFirstFailureException() { return firstFailureException; }
    public void setFirstFailureException(String v) { firstFailureException = v; }
    public String getFirstFailureDocId() { return firstFailureDocId; }
    public void setFirstFailureDocId(String v) { firstFailureDocId = v; }
    public Integer getFirstFailureHttpStatus() { return firstFailureHttpStatus; }
    public void setFirstFailureHttpStatus(Integer v) { firstFailureHttpStatus = v; }
    public Instant getFirstSeen() { return firstSeen; }
    public void setFirstSeen(Instant v) { firstSeen = v; }
    public Instant getLastSeen() { return lastSeen; }
    public void setLastSeen(Instant v) { lastSeen = v; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal v) { amount = v; }
    public String getCurrency() { return currency; }
    public void setCurrency(String v) { currency = v; }
    public String getCounterpartyHash() { return counterpartyHash; }
    public void setCounterpartyHash(String v) { counterpartyHash = v; }
    public String getCounterpartyLast4() { return counterpartyLast4; }
    public void setCounterpartyLast4(String v) { counterpartyLast4 = v; }
    public BigDecimal getObservedRatio() { return observedRatio; }
    public void setObservedRatio(BigDecimal v) { observedRatio = v; }
    public int getExcludedCount() { return excludedCount; }
    public void setExcludedCount(int v) { excludedCount = v; }
    public boolean isTruncated() { return truncated; }
    public void setTruncated(boolean v) { truncated = v; }
    public int getAssemblyVersion() { return assemblyVersion; }
    public void setAssemblyVersion(int v) { assemblyVersion = v; }
    public String getConfigHash() { return configHash; }
    public void setConfigHash(String v) { configHash = v; }
    public Instant getAssembledAt() { return assembledAt; }
    public void setAssembledAt(Instant v) { assembledAt = v; }
}
