package com.ielop.platform.trace.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** One stage of a trace, in flow-definition order (table {@code trace_stage}). */
@Entity
@Table(name = "trace_stage")
public class TraceStageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "trace_id", nullable = false)
    private Long traceId;
    @Column(name = "stage", nullable = false, length = 40)
    private String stage;
    @Column(name = "stage_order", nullable = false)
    private int stageOrder;
    @Column(name = "status", nullable = false, length = 16)
    private String status;
    @Column(name = "user_id", length = 64)
    private String userId;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "ended_at")
    private Instant endedAt;
    @Column(name = "error_count", nullable = false)
    private int errorCount;

    public Long getId() { return id; }
    public Long getTraceId() { return traceId; }
    public void setTraceId(Long v) { traceId = v; }
    public String getStage() { return stage; }
    public void setStage(String v) { stage = v; }
    public int getStageOrder() { return stageOrder; }
    public void setStageOrder(int v) { stageOrder = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public String getUserId() { return userId; }
    public void setUserId(String v) { userId = v; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant v) { startedAt = v; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant v) { endedAt = v; }
    public int getErrorCount() { return errorCount; }
    public void setErrorCount(int v) { errorCount = v; }
}
