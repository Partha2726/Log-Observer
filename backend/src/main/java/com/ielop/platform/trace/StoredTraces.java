package com.ielop.platform.trace;

import com.ielop.platform.trace.assembly.Assembler;
import com.ielop.platform.trace.assembly.AssemblyResult;
import com.ielop.platform.trace.assembly.StageResult;
import com.ielop.platform.trace.entity.TraceActorEntity;
import com.ielop.platform.trace.entity.TraceEntity;
import com.ielop.platform.trace.entity.TraceEventLinkEntity;
import com.ielop.platform.trace.entity.TraceStageEntity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** The rows {@link TraceStore#save} would write for an assembly result, without a database (graph tests, previews). */
public final class StoredTraces {

    private StoredTraces() {
    }

    public static StoredTrace of(AssemblyResult r, Instant assembledAt) {
        TraceEntity t = new TraceEntity();
        t.setFlowRef(r.flowRef());
        t.setFlowId(r.flowId());
        t.setFlowVersion(r.flowVersion());
        t.setOverallStatus(r.overallStatus());
        if (r.firstFailure() != null) {
            t.setFirstFailureStage(r.firstFailure().stage());
            t.setFirstFailureComponent(r.firstFailure().component());
            t.setFirstFailureException(r.firstFailure().exception());
            t.setFirstFailureDocId(r.firstFailure().docId());
            t.setFirstFailureHttpStatus(r.firstFailure().httpStatus());
        }
        t.setFirstSeen(r.firstSeen());
        t.setLastSeen(r.lastSeen());
        t.setObservedRatio(r.observedRatio());
        t.setConfigHash(r.configHash());
        t.setAssemblyVersion(Assembler.ASSEMBLY_VERSION);
        t.setAssembledAt(assembledAt);
        List<TraceStageEntity> stages = new ArrayList<>();
        for (StageResult s : r.stages()) {
            TraceStageEntity e = new TraceStageEntity();
            e.setStage(s.name());
            e.setStageOrder(s.order());
            e.setStatus(s.status().name());
            e.setUserId(s.userId());
            e.setStartedAt(s.startedAt());
            e.setEndedAt(s.endedAt());
            e.setErrorCount(s.errorCount());
            stages.add(e);
        }
        List<TraceActorEntity> actors = r.actors().entrySet().stream().map(a -> new TraceActorEntity(null, a.getKey(), a.getValue())).toList();
        List<TraceEventLinkEntity> links = r.links().stream().map(l -> TraceStore.toRow(null, l)).toList();
        return new StoredTrace(t, stages, actors, links);
    }
}
