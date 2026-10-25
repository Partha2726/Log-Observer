package com.ielop.platform.trace.graph;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.util.List;

/**
 * The frozen graph contract, {@code graph_version 1} (spec section 7.6): the only
 * data the React Flow view renders. Lanes are the flow definition's stages in order.
 * Times are ISO-8601 UTC strings with milliseconds.
 */
@JsonPropertyOrder({"graph_version", "flowId", "flowRef", "overallStatus", "lanes", "nodes", "edges"})
public record TraceGraph(
        @JsonProperty("graph_version") int graphVersion,
        String flowId,
        String flowRef,
        String overallStatus,
        List<Lane> lanes,
        List<Node> nodes,
        List<Edge> edges
) {
    public static final int VERSION = 1;

    @JsonPropertyOrder({"stage", "status", "userId", "startedAt", "endedAt", "durationMs"})
    public record Lane(String stage, String status, String userId, String startedAt, String endedAt, Long durationMs) {
    }

    /** {@code kind}: ACTOR, SERVICE, COMPONENT, EXTERNAL or PLACEHOLDER; {@code status}: OK, WARN, FAILED, REJECTED, NOT_REACHED. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonPropertyOrder({"id", "kind", "stage", "label", "service", "logger", "status", "exceptionType", "eventCount",
            "firstTs", "lastTs", "evidenceIds", "anomalyIds", "minConfidence", "isFirstFailure"})
    public record Node(
            String id,
            String kind,
            String stage,
            String label,
            String service,
            String logger,
            String status,
            String exceptionType,
            int eventCount,
            String firstTs,
            String lastTs,
            @JsonInclude(JsonInclude.Include.ALWAYS) List<String> evidenceIds,
            @JsonInclude(JsonInclude.Include.ALWAYS) List<Long> anomalyIds,
            BigDecimal minConfidence,
            @JsonProperty("isFirstFailure") boolean isFirstFailure
    ) {
    }

    /** {@code kind}: sequence, handoff, call or integration. */
    @JsonPropertyOrder({"id", "source", "target", "kind", "label", "evidenceIds"})
    public record Edge(String id, String source, String target, String kind, String label, List<String> evidenceIds) {
    }
}
