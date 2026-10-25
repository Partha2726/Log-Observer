package com.ielop.platform.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ielop.platform.flowdef.FlowDefinitionRegistry;
import com.ielop.platform.logsearch.EsClientConfig;
import com.ielop.platform.logsearch.EsUnavailableException;
import com.ielop.platform.logsearch.LogQueryService;
import com.ielop.platform.logsearch.LogSearchProperties;
import com.ielop.platform.support.ElasticsearchIntegrationTest;
import com.ielop.platform.support.EsFixtures;
import com.ielop.platform.trace.assembly.AssemblyResult;
import com.ielop.platform.trace.entity.TraceEventLinkEntity;
import com.ielop.platform.trace.repo.TraceActorRepository;
import com.ielop.platform.trace.repo.TraceEventLinkRepository;
import com.ielop.platform.trace.repo.TraceRepository;
import com.ielop.platform.trace.repo.TraceStageRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Stored-or-assembled traces against Elasticsearch and Postgres (T12: CONC-001, TRACE-030, TRACE-031, F-01). */
class TraceServiceTest extends ElasticsearchIntegrationTest {

    @Autowired
    private TraceService service;
    @Autowired
    private FlowDefinitionRegistry flows;
    @Autowired
    private EsTraceEvidenceSource evidence;
    @Autowired
    private LogQueryService logs;
    @Autowired
    private TraceProperties props;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private TraceRepository traces;
    @Autowired
    private TraceStageRepository stages;
    @Autowired
    private TraceActorRepository actors;
    @Autowired
    private TraceEventLinkRepository links;
    @Autowired
    private JdbcTemplate jdbc;

    private static String flowRef(String scenario) {
        return EsFixtures.demoFlows().stream().filter(f -> f.get("scenario").asText().equals(scenario))
                .map(f -> f.get("flowRef").asText()).findFirst().orElseThrow();
    }

    /** Removes a stored trace and the rows that reference it, so a test starts from "never assembled" whatever ran before. */
    private void forget(String ref) {
        jdbc.update("DELETE FROM anomaly WHERE trace_id IN (SELECT id FROM trace WHERE flow_ref = ?)", ref);
        traces.findByFlowRef(ref).ifPresent(traces::delete);
    }

    private static List<String> normalRefs() {
        return EsFixtures.demoFlows().stream().filter(f -> f.get("scenario").asText().equals("NORMAL"))
                .map(f -> f.get("flowRef").asText()).toList();
    }

    @Test
    void assemblesOncePersistsAndThenServesTheStoredTrace() {
        String ref = flowRef("FAIL_DB_TIMEOUT_APPROVE");
        forget(ref);
        long before = service.assemblyCount();

        StoredTrace first = service.getOrAssemble(ref, false);
        StoredTrace second = service.getOrAssemble(ref, false);

        assertThat(service.assemblyCount() - before).isEqualTo(1);
        assertThat(first.trace().getOverallStatus()).isEqualTo("FAILED");
        assertThat(first.trace().getFirstFailureStage()).isEqualTo("Approve");
        assertThat(first.trace().getFirstFailureComponent()).isEqualTo("c.a.b.l.LimitCheck");
        assertThat(first.trace().getFirstFailureException()).isEqualTo("java.sql.SQLTimeoutException");
        assertThat(first.stages()).extracting(s -> s.getStatus()).containsExactly("COMPLETED", "COMPLETED", "FAILED", "NOT_REACHED");
        assertThat(first.actors()).extracting(a -> a.getRole()).contains("requester", "approver");
        assertThat(first.links()).isNotEmpty();
        assertThat(second.links()).extracting(TraceEventLinkEntity::getEsDocId)
                .containsExactlyElementsOf(first.links().stream().map(TraceEventLinkEntity::getEsDocId).toList());
        assertThat(first.trace().getConfigHash()).hasSize(64);

        StoredTrace rebuilt = service.getOrAssemble(ref, true);
        assertThat(service.assemblyCount() - before).isEqualTo(2);
        assertThat(rebuilt.trace().getId()).as("the trace row keeps its ID").isEqualTo(first.trace().getId());
        assertThat(rebuilt.links()).hasSameSizeAs(first.links());
    }

    @Test
    void parallelRequestsForOneFlowRefAssembleOnce() throws Exception {
        String ref = normalRefs().get(1);
        forget(ref);
        long before = service.assemblyCount();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            List<Callable<StoredTrace>> calls = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                calls.add(() -> service.getOrAssemble(ref, false));
            }
            List<StoredTrace> results = new ArrayList<>();
            for (Future<StoredTrace> f : pool.invokeAll(calls)) {
                results.add(f.get());
            }
            assertThat(service.assemblyCount() - before).isEqualTo(1);
            List<String> expected = results.get(0).links().stream().map(TraceEventLinkEntity::getEsDocId).toList();
            for (StoredTrace r : results) {
                assertThat(r.trace().getId()).isEqualTo(results.get(0).trace().getId());
                assertThat(r.links().stream().map(TraceEventLinkEntity::getEsDocId).toList()).isEqualTo(expected);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aNewLineForTheFlowRefMakesTheStoredTraceStale() throws Exception {
        String ref = normalRefs().get(2);
        StoredTrace first = service.getOrAssemble(ref, false);
        long before = service.assemblyCount();

        EsFixtures.Doc template = EsFixtures.demoDocs().stream().filter(d -> d.list("flow.refs").contains(ref)
                && "D".equals(d.text("log_format"))).findFirst().orElseThrow();
        ObjectNode late = template.source().deepCopy();
        late.put("ingested_at", Instant.now().plus(Duration.ofHours(1)).toString());
        late.with("log").put("offset", 99_999_999L);
        late.put("message_body", "Late line for " + ref);
        String id = "late-line-" + ref;
        index(List.of(new EsFixtures.Doc(id, template.index(), late, template.truth())));
        try {
            StoredTrace second = service.getOrAssemble(ref, false);
            assertThat(service.assemblyCount() - before).isEqualTo(1);
            assertThat(second.links()).extracting(TraceEventLinkEntity::getEsDocId).contains(id);
            assertThat(second.links()).hasSize(first.links().size() + 1);
        } finally {
            deleteDocs(template.index(), List.of(id));
            service.getOrAssemble(ref, true);
        }
    }

    @Test
    void aFailureDuringTheWriteLeavesThePreviousTraceIntact() {
        String ref = flowRef("APPROVER_REJECT");
        StoredTrace before = service.getOrAssemble(ref, false);
        TraceStore failing = new TraceStore(traces, stages, actors, links, jdbc) {
            @Override
            public void save(AssemblyResult r, Instant assembledAt) {
                super.save(r, assembledAt);
                throw new IllegalStateException("simulated Postgres failure after the links were rewritten");
            }
        };
        TraceService broken = new TraceService(flows, evidence, logs, failing, props, tx, new SimpleMeterRegistry(), e -> { });

        assertThatThrownBy(() -> broken.getOrAssemble(ref, true)).isInstanceOf(IllegalStateException.class);

        StoredTrace after = service.findStored(ref).orElseThrow();
        assertThat(after.trace().getAssembledAt()).isEqualTo(before.trace().getAssembledAt());
        assertThat(after.links()).extracting(TraceEventLinkEntity::getEsDocId)
                .containsExactlyElementsOf(before.links().stream().map(TraceEventLinkEntity::getEsDocId).toList());
        assertThat(after.trace().getOverallStatus()).isEqualTo("REJECTED");
    }

    @Test
    void storedTracesAreServedWhileElasticsearchIsDownAndNewOnesFailWith503() throws Exception {
        String stored = flowRef("SESSION_TIMEOUT");
        service.getOrAssemble(stored, false);
        String neverAssembled = flowRef("FAIL_VALIDATION_CREATE");
        forget(neverAssembled);

        var deadProps = new LogSearchProperties("http://127.0.0.1:1", "logs-flow-*", "", "", Duration.ofSeconds(2), 1000, 10_000, 5_000);
        var config = new EsClientConfig();
        try (var rest = config.elasticsearchRestClient(deadProps)) {
            var deadLogs = new LogQueryService(config.elasticsearchClient(rest), deadProps, new SimpleMeterRegistry());
            TraceService offline = new TraceService(flows, new EsTraceEvidenceSource(deadLogs), deadLogs,
                    new TraceStore(traces, stages, actors, links, jdbc), props, tx, new SimpleMeterRegistry(), e -> { });

            assertThat(offline.getOrAssemble(stored, false).trace().getFlowRef()).isEqualTo(stored);
            assertThatThrownBy(() -> offline.getOrAssemble(neverAssembled, false)).isInstanceOf(EsUnavailableException.class);
        }
    }

    @Test
    void unknownReferencesAreNotFound() {
        assertThatThrownBy(() -> service.getOrAssemble("REQ-20260515-999999", false))
                .isInstanceOf(com.ielop.platform.trace.assembly.TraceNotFoundException.class);
        assertThatThrownBy(() -> service.getOrAssemble("NOT-A-REF", false))
                .isInstanceOf(com.ielop.platform.trace.assembly.TraceNotFoundException.class);
    }

    static ObjectMapper json() {
        return new ObjectMapper();
    }
}
