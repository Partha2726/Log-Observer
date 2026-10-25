package com.ielop.platform.trace;

import com.ielop.platform.flowdef.FlowDefinition;
import com.ielop.platform.flowdef.FlowDefinitionRegistry;
import com.ielop.platform.logsearch.EsUnavailableException;
import com.ielop.platform.logsearch.LogCriteria;
import com.ielop.platform.logsearch.LogQueryService;
import com.ielop.platform.trace.assembly.Assembler;
import com.ielop.platform.trace.assembly.AssemblyResult;
import com.ielop.platform.trace.assembly.ConfigHash;
import com.ielop.platform.trace.assembly.TraceEvidenceSource;
import com.ielop.platform.trace.assembly.TraceNotFoundException;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stored-or-assembled traces (sections 4.5.3, 7.2 and 7.7). A stored trace is
 * served when its configuration hash is current and no newer lines exist for its
 * flowRef; otherwise it is re-assembled under a per-flowRef advisory lock, and the
 * trace, stages, actors and links are replaced in one transaction.
 */
@Service
@EnableConfigurationProperties(TraceProperties.class)
public class TraceService {

    private static final Logger log = LoggerFactory.getLogger(TraceService.class);

    private final FlowDefinitionRegistry flows;
    private final TraceEvidenceSource evidence;
    private final LogQueryService logs;
    private final TraceStore store;
    private final TraceProperties props;
    private final Assembler assembler;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final AtomicLong assemblies = new AtomicLong();

    public TraceService(FlowDefinitionRegistry flows, TraceEvidenceSource evidence, LogQueryService logs, TraceStore store,
                        TraceProperties props, TransactionTemplate tx, MeterRegistry meters, ApplicationEventPublisher events) {
        this.flows = flows;
        this.evidence = evidence;
        this.logs = logs;
        this.store = store;
        this.props = props;
        this.assembler = new Assembler(props);
        this.tx = tx;
        this.meters = meters;
        this.events = events;
        this.clock = Clock.systemUTC();
    }

    /** Number of assemblies run by this instance (for tests and diagnostics). */
    public long assemblyCount() {
        return assemblies.get();
    }

    public FlowDefinition definitionFor(String flowRef) {
        return flows.forFlowRef(flowRef).orElseThrow(() -> new TraceNotFoundException(flowRef));
    }

    /** The stored trace if it is current, without touching Elasticsearch; empty otherwise. */
    public Optional<StoredTrace> findStored(String flowRef) {
        return store.find(flowRef);
    }

    public StoredTrace getOrAssemble(String flowRef, boolean rebuild) {
        FlowDefinition def = definitionFor(flowRef);
        String hash = ConfigHash.of(Assembler.ASSEMBLY_VERSION, props, def);
        Instant requested = clock.instant();
        if (!rebuild) {
            Optional<StoredTrace> stored = store.find(flowRef);
            if (stored.isPresent() && isCurrent(stored.get(), hash)) {
                return stored.get();
            }
        }
        return tx.execute(status -> {
            store.lock(flowRef);
            Optional<StoredTrace> again = store.find(flowRef);
            if (again.isPresent() && isCurrent(again.get(), hash)
                    && (!rebuild || !again.get().trace().getAssembledAt().isBefore(requested))) {
                return again.get();
            }
            AssemblyResult result = assemble(flowRef, def);
            store.save(result, clock.instant());
            StoredTrace saved = store.find(flowRef).orElseThrow();
            events.publishEvent(new TraceAssembledEvent(saved.trace().getId(), flowRef));
            return saved;
        });
    }

    AssemblyResult assemble(String flowRef, FlowDefinition def) {
        Timer.Sample sample = Timer.start(meters);
        try {
            AssemblyResult result = assembler.assemble(flowRef, def, evidence);
            assemblies.incrementAndGet();
            DistributionSummary.builder("trace.assembly.hits").register(meters).record(result.links().size());
            DistributionSummary.builder("trace.assembly.observed_ratio").register(meters).record(result.observedRatio().doubleValue());
            return result;
        } finally {
            sample.stop(meters.timer("trace.assembly.duration"));
        }
    }

    /** Current when the configuration hash matches and no line for the flowRef was ingested after assembly (F-09). */
    private boolean isCurrent(StoredTrace stored, String hash) {
        if (!hash.equals(stored.trace().getConfigHash())) {
            return false;
        }
        try {
            return logs.maxIngestedAt("trace.staleness", LogCriteria.builder().flowRef(stored.trace().getFlowRef()).build())
                    .map(latest -> !latest.isAfter(stored.trace().getAssembledAt()))
                    .orElse(true);
        } catch (EsUnavailableException e) {
            // Postgres-only reads keep working when Elasticsearch is down (NFR-06).
            log.debug("Staleness check skipped for {}: {}", stored.trace().getFlowRef(), e.getMessage());
            return true;
        }
    }
}
