package com.ielop.platform.trace.api;

import com.ielop.platform.common.PageResponse;
import com.ielop.platform.common.exception.ApiException;
import com.ielop.platform.flowdef.FlowDefinition;
import com.ielop.platform.logsearch.LogHit;
import com.ielop.platform.logsearch.LogQueryService;
import com.ielop.platform.trace.StoredTrace;
import com.ielop.platform.trace.TraceAnomalies;
import com.ielop.platform.trace.TraceService;
import com.ielop.platform.trace.assembly.TraceNotFoundException;
import com.ielop.platform.trace.entity.TraceActorEntity;
import com.ielop.platform.trace.entity.TraceEntity;
import com.ielop.platform.trace.entity.TraceEventLinkEntity;
import com.ielop.platform.trace.entity.TraceStageEntity;
import com.ielop.platform.trace.graph.GraphBuilder;
import com.ielop.platform.trace.graph.TraceGraph;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Trace endpoints (section 8.8): the assembled trace (assembling on demand), its
 * evidence lines and its graph. The graph and the stored trace are read from Postgres
 * alone; only assembly and the evidence message text need Elasticsearch.
 */
@RestController
@Tag(name = "Traces")
@RequestMapping("/api/traces")
public class TraceController {

    private final TraceService traces;
    private final LogQueryService logs;
    private final ApiParams params;
    private final GraphBuilder graphs;
    private final ObjectProvider<TraceAnomalies> anomalies;

    public TraceController(TraceService traces, LogQueryService logs, ApiParams params, ObjectProvider<TraceAnomalies> anomalies,
                           @Value("${app.graph.max-nodes-per-stage:12}") int maxNodesPerStage) {
        this.traces = traces;
        this.logs = logs;
        this.params = params;
        this.anomalies = anomalies;
        this.graphs = new GraphBuilder(maxNodesPerStage);
    }

    @Operation(summary = "Assembled trace: stages, actors, status, first failure (assembles on demand)")
    @GetMapping("/{flowRef}")
    public ApiDtos.Trace trace(@PathVariable String flowRef, @RequestParam(defaultValue = "false") boolean rebuild) {
        ApiParams.Check check = params.check();
        check.flowRef("flowRef", flowRef, true);
        check.done();
        StoredTrace stored = traces.getOrAssemble(flowRef, rebuild);
        return toDto(stored, traces.definitionFor(flowRef), anomaliesOf(stored));
    }

    @Operation(summary = "Trace graph, graph_version 1, from Postgres only")
    @GetMapping("/{flowRef}/graph")
    public TraceGraph graph(@PathVariable String flowRef) {
        ApiParams.Check check = params.check();
        check.flowRef("flowRef", flowRef, true);
        check.done();
        StoredTrace stored = traces.findStored(flowRef).orElseThrow(() -> new TraceNotFoundException(flowRef));
        Map<String, List<Long>> byDoc = new LinkedHashMap<>();
        for (TraceAnomalies.View a : anomaliesOf(stored)) {
            a.evidenceDocIds().forEach(id -> byDoc.computeIfAbsent(id, k -> new ArrayList<>()).add(a.id()));
        }
        return graphs.build(stored, traces.definitionFor(flowRef), byDoc);
    }

    private List<TraceAnomalies.View> anomaliesOf(StoredTrace stored) {
        TraceAnomalies source = anomalies.getIfAvailable();
        return source == null ? List.of() : source.forTrace(stored.trace().getId());
    }

    @Operation(summary = "Evidence lines with class and confidence; message text by document ID")
    @GetMapping("/{flowRef}/events")
    public PageResponse<ApiDtos.EvidenceLine> events(@PathVariable String flowRef,
                                                     @RequestParam(required = false) String stage,
                                                     @RequestParam(required = false) String evidenceClass,
                                                     @RequestParam(required = false) String nodeId,
                                                     @RequestParam(required = false) Integer page,
                                                     @RequestParam(required = false) Integer size) {
        ApiParams.Check check = params.check();
        check.flowRef("flowRef", flowRef, true);
        check.oneOf("evidenceClass", evidenceClass, List.of("OBSERVED", "CORRELATED"));
        check.text("nodeId", nodeId, 80);
        int p = check.page(page);
        int s = check.size(size);
        check.done();

        StoredTrace stored = traces.getOrAssemble(flowRef, false);
        FlowDefinition def = traces.definitionFor(flowRef);
        if (stage != null && def.stage(stage).isEmpty() && !FlowDefinition.SIGN_OUT.equals(stage)) {
            throw ApiException.validation(Map.of("stage", "is not a stage of flow definition " + def.id()));
        }
        Set<String> nodeEvidence = null;
        if (nodeId != null) {
            nodeEvidence = graphs.build(stored, def).nodes().stream().filter(n -> n.id().equals(nodeId)).findFirst()
                    .map(n -> Set.copyOf(n.evidenceIds()))
                    .orElseThrow(() -> ApiException.notFound("No node " + nodeId + " in the graph of " + flowRef));
        }
        Set<String> nodeFilter = nodeEvidence;
        List<TraceEventLinkEntity> matching = stored.links().stream()
                .filter(l -> stage == null || stage.equals(l.getStage()))
                .filter(l -> evidenceClass == null || evidenceClass.equals(l.getEvidenceClass()))
                .filter(l -> nodeFilter == null || nodeFilter.contains(l.getEsDocId()))
                .toList();
        List<TraceEventLinkEntity> pageLinks = matching.stream().skip((long) p * s).limit(s).toList();
        Map<String, LogHit> docs = logs.getByIds("trace.events", pageLinks.stream().map(TraceEventLinkEntity::getEsDocId).toList())
                .stream().collect(Collectors.toMap(LogHit::id, Function.identity()));
        List<ApiDtos.EvidenceLine> items = pageLinks.stream().map(l -> evidence(l, docs.get(l.getEsDocId()))).toList();
        return PageResponse.of(items, p, s, matching.size(), false);
    }

    static ApiDtos.EvidenceLine evidence(TraceEventLinkEntity l, LogHit h) {
        if (h == null) {
            return new ApiDtos.EvidenceLine(l.getEsDocId(), l.getEventTs(), l.getService(), l.getHost(), l.getStage(), null, null,
                    l.getLogger(), l.getThread(), l.getRequestId(), l.getEvidenceClass(), l.getConfidence(), l.getLinkReason(),
                    null, Map.of(), l.isStacktrace(), true);
        }
        Map<String, Object> parsed = new LinkedHashMap<>();
        put(parsed, "eventAction", h.eventAction());
        put(parsed, "userId", h.userId());
        put(parsed, "sessionId", h.sessionId());
        put(parsed, "flowRefs", h.flowRefs().isEmpty() ? null : h.flowRefs());
        put(parsed, "clientIp", h.clientIp());
        put(parsed, "httpMethod", h.httpMethod());
        put(parsed, "urlPath", h.urlPath());
        put(parsed, "httpStatus", h.httpStatus());
        put(parsed, "durationSeconds", h.durationSeconds());
        put(parsed, "userAgent", h.userAgent());
        put(parsed, "errorType", h.errorType());
        put(parsed, "providerStatus", h.providerStatus());
        put(parsed, "externalCorrelationIds", h.externalCorrelationIds().isEmpty() ? null : h.externalCorrelationIds());
        put(parsed, "amount", h.amount());
        put(parsed, "currency", h.currency());
        put(parsed, "counterpartyLast4", h.counterpartyLast4());
        put(parsed, "reason", h.reason());
        put(parsed, "permission", h.permission());
        put(parsed, "resource", h.resource());
        put(parsed, "messageBody", h.messageBody());
        return new ApiDtos.EvidenceLine(h.id(), h.timestamp(), h.service(), h.host(), l.getStage(), sourceFile(h.filePath()),
                h.level(), h.logger(), h.thread(), h.requestId(), l.getEvidenceClass(), l.getConfidence(), l.getLinkReason(),
                h.message(), parsed, h.hasStacktrace(), false);
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) {
            m.put(k, v);
        }
    }

    /** {@code /logs/billing-service/billing-bill-01-2026-05-15.log} → {@code billing-service/billing-bill-01-2026-05-15.log}. */
    static String sourceFile(String path) {
        if (path == null) {
            return null;
        }
        String[] parts = path.split("/");
        return parts.length >= 2 ? parts[parts.length - 2] + "/" + parts[parts.length - 1] : path;
    }

    static ApiDtos.Trace toDto(StoredTrace s, FlowDefinition def, List<TraceAnomalies.View> anomalies) {
        TraceEntity t = s.trace();
        Map<String, String> byRole = new LinkedHashMap<>();
        for (TraceActorEntity a : s.actors()) {
            byRole.put(a.getRole(), a.getUserId());
        }
        // Roles in flow-definition order.
        Map<String, String> actors = new LinkedHashMap<>();
        def.roles().stream().filter(byRole::containsKey).forEach(r -> actors.put(r, byRole.get(r)));
        byRole.forEach(actors::putIfAbsent);
        List<ApiDtos.Stage> stages = s.stages().stream().map(TraceController::stage).toList();
        ApiDtos.FirstFailure ff = t.getFirstFailureStage() == null ? null : new ApiDtos.FirstFailure(t.getFirstFailureStage(),
                t.getFirstFailureComponent(), t.getFirstFailureException(), t.getFirstFailureHttpStatus(), t.getFirstFailureDocId());
        return new ApiDtos.Trace(t.getFlowRef(), t.getFlowId(), t.getFlowVersion(), t.getOverallStatus(), actors,
                t.getRequesterUserId(), t.getApproverUserId(), t.getExecutorUserId(), t.getAmount(), t.getCurrency(),
                t.getCounterpartyLast4(), t.getFirstSeen(), t.getLastSeen(), Duration.between(t.getFirstSeen(), t.getLastSeen()).toMillis(),
                stages, ff, anomalies, t.getObservedRatio(), t.getExcludedCount(), t.isTruncated(), s.links().size(),
                t.getAssembledAt(), t.getAssemblyVersion());
    }

    private static ApiDtos.Stage stage(TraceStageEntity e) {
        Long duration = e.getStartedAt() == null || e.getEndedAt() == null ? null : Duration.between(e.getStartedAt(), e.getEndedAt()).toMillis();
        return new ApiDtos.Stage(e.getStage(), e.getStageOrder(), e.getStatus(), e.getUserId(), e.getStartedAt(), e.getEndedAt(),
                duration, e.getErrorCount());
    }

    static ApiException unavailable(String message) {
        return new ApiException("ES_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE, message);
    }
}
