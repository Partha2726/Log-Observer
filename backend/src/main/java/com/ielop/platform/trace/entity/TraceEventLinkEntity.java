package com.ielop.platform.trace.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * One log line linked to a trace, with its evidence class, confidence and the
 * parsed fields the graph and evidence filters need (table {@code trace_event_link}).
 * {@code esDocId} points into Elasticsearch; the message text stays there.
 */
@Entity
@Table(name = "trace_event_link")
@IdClass(TraceEventLinkEntity.Key.class)
public class TraceEventLinkEntity {

    @Id
    @Column(name = "trace_id", nullable = false)
    private Long traceId;
    @Id
    @Column(name = "es_doc_id", nullable = false, length = 64)
    private String esDocId;
    @Column(name = "stage", nullable = false, length = 40)
    private String stage;
    @Column(name = "service", nullable = false, length = 64)
    private String service;
    @Column(name = "evidence_class", nullable = false, length = 12)
    private String evidenceClass;
    @Column(name = "confidence", nullable = false, precision = 3, scale = 2)
    private BigDecimal confidence;
    @Column(name = "link_reason", nullable = false, length = 20)
    private String linkReason;
    @Column(name = "event_ts", nullable = false)
    private Instant eventTs;
    @Column(name = "seq", nullable = false)
    private int seq;
    @Column(name = "host", length = 255)
    private String host;
    @Column(name = "logger", length = 255)
    private String logger;
    @Column(name = "thread", length = 255)
    private String thread;
    @Column(name = "request_id", length = 64)
    private String requestId;
    @Column(name = "session_id", length = 64)
    private String sessionId;
    @Column(name = "user_id", length = 64)
    private String userId;
    @Column(name = "event_action", length = 255)
    private String eventAction;
    @Column(name = "http_method", length = 16)
    private String httpMethod;
    @Column(name = "url_path", length = 2048)
    private String urlPath;
    @Column(name = "http_status")
    private Integer httpStatus;
    @Column(name = "error_type", length = 255)
    private String errorType;
    @Column(name = "provider_status", length = 8)
    private String providerStatus;
    @Column(name = "external_ids", length = 255)
    private String externalIds;
    @Column(name = "is_error", nullable = false)
    private boolean error;
    @Column(name = "is_warning", nullable = false)
    private boolean warning;
    @Column(name = "has_stacktrace", nullable = false)
    private boolean stacktrace;

    public Long getTraceId() { return traceId; }
    public void setTraceId(Long v) { traceId = v; }
    public String getEsDocId() { return esDocId; }
    public void setEsDocId(String v) { esDocId = v; }
    public String getStage() { return stage; }
    public void setStage(String v) { stage = v; }
    public String getService() { return service; }
    public void setService(String v) { service = v; }
    public String getEvidenceClass() { return evidenceClass; }
    public void setEvidenceClass(String v) { evidenceClass = v; }
    public BigDecimal getConfidence() { return confidence; }
    public void setConfidence(BigDecimal v) { confidence = v; }
    public String getLinkReason() { return linkReason; }
    public void setLinkReason(String v) { linkReason = v; }
    public Instant getEventTs() { return eventTs; }
    public void setEventTs(Instant v) { eventTs = v; }
    public int getSeq() { return seq; }
    public void setSeq(int v) { seq = v; }
    public String getHost() { return host; }
    public void setHost(String v) { host = v; }
    public String getLogger() { return logger; }
    public void setLogger(String v) { logger = v; }
    public String getThread() { return thread; }
    public void setThread(String v) { thread = v; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String v) { requestId = v; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String v) { sessionId = v; }
    public String getUserId() { return userId; }
    public void setUserId(String v) { userId = v; }
    public String getEventAction() { return eventAction; }
    public void setEventAction(String v) { eventAction = v; }
    public String getHttpMethod() { return httpMethod; }
    public void setHttpMethod(String v) { httpMethod = v; }
    public String getUrlPath() { return urlPath; }
    public void setUrlPath(String v) { urlPath = v; }
    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer v) { httpStatus = v; }
    public String getErrorType() { return errorType; }
    public void setErrorType(String v) { errorType = v; }
    public String getProviderStatus() { return providerStatus; }
    public void setProviderStatus(String v) { providerStatus = v; }
    public String getExternalIds() { return externalIds; }
    public void setExternalIds(String v) { externalIds = v; }
    public boolean isError() { return error; }
    public void setError(boolean v) { error = v; }
    public boolean isWarning() { return warning; }
    public void setWarning(boolean v) { warning = v; }
    public boolean isStacktrace() { return stacktrace; }
    public void setStacktrace(boolean v) { stacktrace = v; }

    public static class Key implements Serializable {
        private Long traceId;
        private String esDocId;

        public Key() {
        }

        public Key(Long traceId, String esDocId) {
            this.traceId = traceId;
            this.esDocId = esDocId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(traceId, k.traceId) && Objects.equals(esDocId, k.esDocId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(traceId, esDocId);
        }
    }
}
