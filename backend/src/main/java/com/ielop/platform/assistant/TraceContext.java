package com.ielop.platform.assistant;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;
import java.util.Map;

/**
 * The only thing a model sees about a trace (section 11.3), already redacted. Evidence IDs
 * ({@code ev1…evN}) map to Elasticsearch document IDs on the server only.
 */
@JsonPropertyOrder({"contextVersion", "trace", "anomalies", "evidence", "truncated"})
public record TraceContext(String contextVersion, Trace trace, List<Anomaly> anomalies, List<Evidence> evidence, Truncation truncated) {

    public static final String VERSION = "1";

    @JsonPropertyOrder({"flowId", "flowRef", "overallStatus", "roles", "requester", "approver", "executor", "stages", "firstFailure"})
    public record Trace(String flowId, String flowRef, String overallStatus, Map<String, String> roles, String requester,
                        String approver, String executor, List<Stage> stages, FirstFailure firstFailure) {
    }

    @JsonPropertyOrder({"stage", "status", "start", "end", "durationMs", "user"})
    public record Stage(String stage, String status, String start, String end, Long durationMs, String user) {
    }

    @JsonPropertyOrder({"stage", "service", "component", "exception"})
    public record FirstFailure(String stage, String service, String component, String exception) {
    }

    @JsonPropertyOrder({"ruleId", "name", "severity", "details"})
    public record Anomaly(String ruleId, String name, String severity, Map<String, Object> details) {
    }

    @JsonPropertyOrder({"id", "stage", "service", "ts", "level", "class", "logger", "httpStatus", "text"})
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Evidence(String id, String stage, String service, String ts, String level,
                           @JsonProperty("class") String evidenceClass, String logger, Integer httpStatus, String text) {
    }

    /** What the builder left out: evidence lines beyond the limits, and texts cut to 600 characters. */
    @JsonPropertyOrder({"evidenceOmitted", "textsCut"})
    public record Truncation(int evidenceOmitted, int textsCut) {
    }
}
