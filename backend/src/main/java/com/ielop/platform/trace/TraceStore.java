package com.ielop.platform.trace;

import com.ielop.platform.trace.assembly.AssemblyResult;
import com.ielop.platform.trace.assembly.FirstFailure;
import com.ielop.platform.trace.assembly.StageResult;
import com.ielop.platform.trace.assembly.TraceLink;
import com.ielop.platform.trace.entity.TraceActorEntity;
import com.ielop.platform.trace.entity.TraceEntity;
import com.ielop.platform.trace.entity.TraceEventLinkEntity;
import com.ielop.platform.trace.entity.TraceStageEntity;
import com.ielop.platform.trace.repo.TraceActorRepository;
import com.ielop.platform.trace.repo.TraceEventLinkRepository;
import com.ielop.platform.trace.repo.TraceRepository;
import com.ielop.platform.trace.repo.TraceStageRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads and writes trace rows (section 7.2, 7.7). {@link #save} replaces a trace's
 * stages, actors and links in the caller's transaction; the trace row keeps its ID,
 * so later rows that reference it survive a re-assembly.
 */
@Component
public class TraceStore {

    private final TraceRepository traces;
    private final TraceStageRepository stages;
    private final TraceActorRepository actors;
    private final TraceEventLinkRepository links;
    private final JdbcTemplate jdbc;

    public TraceStore(TraceRepository traces, TraceStageRepository stages, TraceActorRepository actors,
                      TraceEventLinkRepository links, JdbcTemplate jdbc) {
        this.traces = traces;
        this.stages = stages;
        this.actors = actors;
        this.links = links;
        this.jdbc = jdbc;
    }

    public Optional<StoredTrace> find(String flowRef) {
        return traces.findByFlowRef(flowRef).map(t -> new StoredTrace(t,
                stages.findByTraceIdOrderByStageOrder(t.getId()),
                actors.findByTraceIdOrderByRole(t.getId()),
                links.findByTraceIdOrderBySeq(t.getId())));
    }

    /** Serialises assembly per flowRef until the transaction ends (section 7.7). */
    public void lock(String flowRef) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext(?))", rs -> null, flowRef);
    }

    public void save(AssemblyResult r, Instant assembledAt) {
        TraceEntity t = traces.findByFlowRef(r.flowRef()).orElseGet(TraceEntity::new);
        t.setFlowRef(r.flowRef());
        t.setFlowId(r.flowId());
        t.setFlowVersion(r.flowVersion());
        // The three trace columns hold the actors of the definition's first three roles (demo: requester, approver, executor).
        t.setRequesterUserId(r.actors().get(roleAt(r, 0)));
        t.setApproverUserId(r.actors().get(roleAt(r, 1)));
        t.setExecutorUserId(r.actors().get(roleAt(r, 2)));
        t.setOverallStatus(r.overallStatus());
        FirstFailure f = r.firstFailure();
        t.setFirstFailureStage(f == null ? null : f.stage());
        t.setFirstFailureComponent(f == null ? null : truncate(f.component(), 255));
        t.setFirstFailureException(f == null ? null : truncate(f.exception(), 255));
        t.setFirstFailureDocId(f == null ? null : f.docId());
        t.setFirstFailureHttpStatus(f == null ? null : f.httpStatus());
        t.setFirstSeen(r.firstSeen());
        t.setLastSeen(r.lastSeen());
        t.setAmount(r.amount());
        t.setCurrency(r.currency());
        t.setCounterpartyHash(r.counterpartyHash());
        t.setCounterpartyLast4(r.counterpartyLast4());
        t.setObservedRatio(r.observedRatio());
        t.setExcludedCount(r.excludedCount());
        t.setTruncated(r.truncated());
        t.setAssemblyVersion(com.ielop.platform.trace.assembly.Assembler.ASSEMBLY_VERSION);
        t.setConfigHash(r.configHash());
        t.setAssembledAt(assembledAt);
        t = traces.saveAndFlush(t);
        Long id = t.getId();

        links.deleteByTraceId(id);
        stages.deleteByTraceId(id);
        actors.deleteByTraceId(id);

        List<TraceStageEntity> stageRows = new ArrayList<>();
        for (StageResult s : r.stages()) {
            TraceStageEntity e = new TraceStageEntity();
            e.setTraceId(id);
            e.setStage(s.name());
            e.setStageOrder(s.order());
            e.setStatus(s.status().name());
            e.setUserId(s.userId());
            e.setStartedAt(s.startedAt());
            e.setEndedAt(s.endedAt());
            e.setErrorCount(s.errorCount());
            stageRows.add(e);
        }
        stages.saveAll(stageRows);
        List<TraceActorEntity> actorRows = new ArrayList<>();
        for (Map.Entry<String, String> a : r.actors().entrySet()) {
            actorRows.add(new TraceActorEntity(id, a.getKey(), a.getValue()));
        }
        actors.saveAll(actorRows);
        List<TraceEventLinkEntity> linkRows = new ArrayList<>(r.links().size());
        for (TraceLink l : r.links()) {
            linkRows.add(toRow(id, l));
        }
        links.saveAll(linkRows);
        links.flush();
    }

    private static String roleAt(AssemblyResult r, int index) {
        return index < r.roles().size() ? r.roles().get(index) : null;
    }

    static TraceEventLinkEntity toRow(Long traceId, TraceLink l) {
        var h = l.hit();
        TraceEventLinkEntity e = new TraceEventLinkEntity();
        e.setTraceId(traceId);
        e.setEsDocId(h.id());
        e.setStage(l.stage());
        e.setService(h.service() == null ? "unknown" : truncate(h.service(), 64));
        e.setEvidenceClass(l.evidenceClass().name());
        e.setConfidence(l.confidence());
        e.setLinkReason(l.reason().name());
        e.setEventTs(h.timestamp());
        e.setSeq(l.seq());
        e.setHost(truncate(h.host(), 255));
        e.setLogger(truncate(h.logger(), 255));
        e.setThread(truncate(h.thread(), 255));
        e.setRequestId(truncate(h.requestId(), 64));
        e.setSessionId(truncate(h.sessionId(), 64));
        e.setUserId(truncate(h.userId(), 64));
        e.setEventAction(truncate(h.eventAction(), 255));
        e.setHttpMethod(truncate(h.httpMethod(), 16));
        e.setUrlPath(truncate(h.urlPath(), 2048));
        e.setHttpStatus(h.httpStatus());
        e.setErrorType(truncate(h.errorType(), 255));
        e.setProviderStatus(truncate(h.providerStatus(), 8));
        e.setExternalIds(h.externalCorrelationIds().isEmpty() ? null : truncate(String.join(",", h.externalCorrelationIds()), 255));
        e.setError(h.isError());
        e.setWarning(h.isWarning());
        e.setStacktrace(h.hasStacktrace());
        return e;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
