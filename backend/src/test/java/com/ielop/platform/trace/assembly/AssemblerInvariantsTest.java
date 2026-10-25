package com.ielop.platform.trace.assembly;

import com.fasterxml.jackson.databind.JsonNode;
import com.ielop.platform.flowdef.FlowDefinition;
import com.ielop.platform.flowdef.FlowDefinitionLoader;
import com.ielop.platform.logsearch.LogHit;
import com.ielop.platform.support.EsFixtures;
import com.ielop.platform.trace.TraceProperties;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.ielop.platform.trace.assembly.Hits.hit;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Assembly invariants (section 7.5: TRACE-010 to 015, 019, 032) and accuracy on the
 * demo fixture, which is real pipeline output of a generated run with ground truth
 * (one flow per phase 1 scenario plus NORMAL flows).
 */
class AssemblerInvariantsTest {

    static final Path FLOWS = Path.of("..", "config", "flows");
    static final FlowDefinition DEMO = FlowDefinitionLoader.loadDirectory(FLOWS).get(0);
    static final List<LogHit> DEMO_HITS = Hits.demo();
    static final Map<String, JsonNode> TRUTH = EsFixtures.demoDocs().stream()
            .collect(Collectors.toMap(EsFixtures.Doc::id, EsFixtures.Doc::truth));
    final Assembler assembler = new Assembler(TraceProperties.defaults());

    static List<String> refs() {
        return EsFixtures.demoFlows().stream().map(f -> f.get("flowRef").asText()).toList();
    }

    AssemblyResult assemble(String ref, List<LogHit> hits) {
        return assembler.assemble(ref, DEMO, new InMemoryEvidenceSource(hits));
    }

    // ------------------------------------------------------------- accuracy on generated data

    @Test
    void fixtureFlowsMatchGroundTruthStatusesAndFirstFailure() {
        for (JsonNode flow : EsFixtures.demoFlows()) {
            String ref = flow.get("flowRef").asText();
            AssemblyResult r = assemble(ref, DEMO_HITS);
            String scenario = flow.get("scenario").asText();

            for (StageResult s : r.stages()) {
                assertThat(s.status().name()).as(scenario + " " + ref + " " + s.name())
                        .isEqualTo(flow.get("stages").get(s.name()).asText());
            }
            assertThat(r.overallStatus()).as(scenario + " overall").isEqualTo(flow.get("overallStatus").asText());
            if (flow.get("expectedFailingStage").isNull()) {
                assertThat(r.firstFailure()).as(scenario).isNull();
            } else {
                assertThat(r.firstFailure().stage()).as(scenario).isEqualTo(flow.get("expectedFailingStage").asText());
                assertThat(r.firstFailure().component()).as(scenario).isEqualTo(flow.get("expectedFailingComponent").asText());
            }
            assertThat(r.actors().get("requester")).as(scenario).isEqualTo(flow.get("requester").asText());
            if (!flow.get("approver").isNull()) {
                assertThat(r.actors().get("approver")).as(scenario).isEqualTo(flow.get("approver").asText());
            }
        }
    }

    @Test
    void fixtureLinksAreAttributedAsTheGroundTruthSays() {
        int observed = 0;
        int observedCorrect = 0;
        int correlated = 0;
        int correlatedCorrect = 0;
        int stageCorrect = 0;
        int truthLines = 0;
        int recalled = 0;
        for (String ref : refs()) {
            AssemblyResult r = assemble(ref, DEMO_HITS);
            Set<String> linkedIds = new HashSet<>();
            for (TraceLink l : r.links()) {
                JsonNode t = TRUTH.get(l.hit().id());
                boolean right = ref.equals(t.get("flowRef").textValue());
                linkedIds.add(l.hit().id());
                if (l.evidenceClass() == EvidenceClass.OBSERVED) {
                    observed++;
                    observedCorrect += right ? 1 : 0;
                } else {
                    correlated++;
                    correlatedCorrect += right ? 1 : 0;
                }
                if (right && l.stage().equals(t.get("stage").textValue())) {
                    stageCorrect++;
                }
            }
            for (var e : TRUTH.entrySet()) {
                if (ref.equals(e.getValue().get("flowRef").textValue())) {
                    truthLines++;
                    recalled += linkedIds.contains(e.getKey()) ? 1 : 0;
                }
            }
        }
        System.out.printf("fixture attribution: OBSERVED %d/%d, CORRELATED %d/%d, stage %d/%d, recall %d/%d%n",
                observedCorrect, observed, correlatedCorrect, correlated, stageCorrect, observed + correlated, recalled, truthLines);
        assertThat(observedCorrect).isGreaterThanOrEqualTo((int) Math.ceil(observed * 0.99));
        assertThat(correlatedCorrect).isGreaterThanOrEqualTo((int) Math.ceil(correlated * 0.85));
        assertThat(recalled).isGreaterThanOrEqualTo((int) Math.ceil(truthLines * 0.95));
        assertThat(stageCorrect).isGreaterThanOrEqualTo((int) Math.ceil((observed + correlated) * 0.95));
    }

    // ------------------------------------------------------------- TRACE-010, TRACE-011

    @Test
    void sameInputGivesTheSameResultWhateverTheHitOrder() {
        for (String ref : refs()) {
            AssemblyResult a = assemble(ref, DEMO_HITS);
            AssemblyResult b = assemble(ref, DEMO_HITS);
            AssemblyResult reversed = assembler.assemble(ref, DEMO, new InMemoryEvidenceSource(DEMO_HITS, true, 5_000));
            List<LogHit> shuffled = new ArrayList<>(DEMO_HITS);
            java.util.Collections.shuffle(shuffled, new java.util.Random(7));
            AssemblyResult shuffledResult = assemble(ref, shuffled);

            assertThat(b).isEqualTo(a);
            assertThat(reversed).isEqualTo(a);
            assertThat(shuffledResult).isEqualTo(a);
        }
    }

    // ------------------------------------------------------------- TRACE-012

    @Test
    void everyLineIsInAtMostOneStageAndEveryCarrierIsLinked() {
        for (String ref : refs()) {
            AssemblyResult r = assemble(ref, DEMO_HITS);
            Set<String> ids = new HashSet<>();
            for (TraceLink l : r.links()) {
                assertThat(ids.add(l.hit().id())).isTrue();
                assertThat(l.stage()).isNotNull();
            }
            Set<String> requestIds = DEMO_HITS.stream().filter(h -> h.flowRefs().contains(ref) && h.requestId() != null)
                    .map(LogHit::requestId).collect(Collectors.toSet());
            for (LogHit h : DEMO_HITS) {
                if (h.flowRefs().contains(ref) || (h.requestId() != null && requestIds.contains(h.requestId()))) {
                    assertThat(ids).as(ref + " must link " + h.id()).contains(h.id());
                }
            }
        }
    }

    // ------------------------------------------------------------- TRACE-013

    @Test
    void aNoiseLineOnASchedulerThreadChangesNothing() {
        for (String ref : refs()) {
            AssemblyResult before = assemble(ref, DEMO_HITS);
            LogHit billing = before.links().stream().map(TraceLink::hit)
                    .filter(h -> "billing-service".equals(h.service()) && h.thread() != null).findFirst().orElse(null);
            if (billing == null) {
                continue;
            }
            List<LogHit> withNoise = new ArrayList<>(DEMO_HITS);
            withNoise.add(hit("noise-1", billing.timestamp().plusMillis(50).toString()).service("billing-service")
                    .host(billing.host()).thread("scheduling-1").logger("c.a.b.j.BudgetSyncJob").build());
            withNoise.add(hit("noise-2", billing.timestamp().plusMillis(60).toString()).service("billing-service")
                    .host(billing.host()).thread(billing.thread().replace("billing-worker", "scheduling")).build());
            assertThat(assemble(ref, withNoise)).isEqualTo(before);
        }
    }

    // ------------------------------------------------------------- TRACE-014

    @Test
    void removingEveryCorrelatedLineNeverChangesStageStatus() {
        for (String ref : refs()) {
            AssemblyResult full = assemble(ref, DEMO_HITS);
            Set<String> correlated = full.links().stream().filter(l -> l.evidenceClass() == EvidenceClass.CORRELATED)
                    .map(l -> l.hit().id()).collect(Collectors.toSet());
            List<LogHit> withoutCorrelated = DEMO_HITS.stream().filter(h -> !correlated.contains(h.id())).toList();
            AssemblyResult reduced = assemble(ref, withoutCorrelated);

            assertThat(reduced.stages()).extracting(StageResult::status)
                    .containsExactlyElementsOf(full.stages().stream().map(StageResult::status).toList());
            assertThat(reduced.overallStatus()).isEqualTo(full.overallStatus());
        }
    }

    // ------------------------------------------------------------- TRACE-015

    @Test
    void configHashChangesWithAnyTraceSettingOrTheFlowDefinitionAndOnlyThen() {
        TraceProperties base = TraceProperties.defaults();
        String hash = ConfigHash.of(1, base, DEMO);

        assertThat(ConfigHash.of(1, TraceProperties.defaults(), DEMO)).isEqualTo(hash);
        assertThat(ConfigHash.of(1, new TraceProperties(2000, 500, 300, 0.3, base.noiseThreads()), DEMO)).isEqualTo(hash);
        List<TraceProperties> changed = List.of(
                new TraceProperties(2001, 500, 300, 0.3, base.noiseThreads()),
                new TraceProperties(2000, 501, 300, 0.3, base.noiseThreads()),
                new TraceProperties(2000, 500, 301, 0.3, base.noiseThreads()),
                new TraceProperties(2000, 500, 300, 0.31, base.noiseThreads()),
                new TraceProperties(2000, 500, 300, 0.3, "^(scheduling)-.*"));
        Set<String> hashes = new HashSet<>();
        for (TraceProperties p : changed) {
            hashes.add(ConfigHash.of(1, p, DEMO));
        }
        assertThat(hashes).hasSize(changed.size()).doesNotContain(hash);

        FlowDefinition edited = new FlowDefinition(DEMO.id(), DEMO.version(), DEMO.refPatternSource(), DEMO.refPattern(),
                DEMO.roles(), DEMO.stages(), DEMO.signOut(), DEMO.businessFields(), "0".repeat(64), DEMO.source());
        assertThat(ConfigHash.of(1, base, edited)).isNotEqualTo(hash);
        assertThat(ConfigHash.of(2, base, DEMO)).isNotEqualTo(hash);
    }

    // ------------------------------------------------------------- TRACE-019

    @Test
    void theTestOnlyTwoStageFlowAssemblesWithoutAnyCodeChange() {
        FlowDefinition tickets = new FlowDefinitionLoader(FLOWS.resolve(FlowDefinitionLoader.SCHEMA_FILE))
                .load(Path.of("src", "test", "resources", "flows", "test-two-stage.yaml"));
        String ref = "TKT-000123";
        List<LogHit> hits = List.of(
                hit("h1", "2026-06-01T09:00:00.100Z").service("ticket-service").host("tk-01").thread("worker-1").ref(ref)
                        .user("usr-5005").rid("q1").action("ticket.opened").build(),
                hit("h2", "2026-06-01T09:00:00.200Z").service("helpdesk-api").host("hd-01").user("usr-5005").rid("q1")
                        .http("POST", "/tickets", 201).build(),
                hit("h3", "2026-06-01T09:00:00.250Z").service("ticket-service").host("tk-01").thread("worker-1").build(),
                hit("h4", "2026-06-01T12:00:00.100Z").service("ticket-service").host("tk-01").thread("worker-2").ref(ref)
                        .user("usr-6006").rid("q2").action("ticket.resolve_failed").errorLevel().build(),
                hit("h5", "2026-06-01T12:00:00.300Z").service("helpdesk-api").host("hd-01").user("usr-6006").rid("q2")
                        .http("POST", "/tickets/" + ref + "/resolve", 500).build());
        AssemblyResult r = assembler.assemble(ref, tickets, new InMemoryEvidenceSource(hits));

        assertThat(r.flowId()).isEqualTo("test-two-stage");
        assertThat(r.stages()).extracting(StageResult::name).containsExactly("Open", "Resolve");
        assertThat(r.stages()).extracting(StageResult::status).containsExactly(StageStatus.COMPLETED, StageStatus.FAILED);
        assertThat(r.actors()).containsExactly(Map.entry("reporter", "usr-5005"), Map.entry("resolver", "usr-6006"));
        assertThat(r.overallStatus()).isEqualTo("FAILED");
        assertThat(r.firstFailure().stage()).isEqualTo("Resolve");
        assertThat(r.links()).filteredOn(l -> l.hit().id().equals("h3")).singleElement()
                .satisfies(l -> assertThat(l.reason()).isEqualTo(LinkReason.THREAD));
    }

    // ------------------------------------------------------------- TRACE-032

    @Test
    void aSeedAboveTheCapMarksTheTraceTruncated() {
        String ref = refs().get(0);
        AssemblyResult r = assembler.assemble(ref, DEMO, new InMemoryEvidenceSource(DEMO_HITS, false, 3));
        assertThat(r.truncated()).isTrue();
        assertThat(assemble(ref, DEMO_HITS).truncated()).isFalse();
    }

    static Map<String, Integer> counts(List<String> values) {
        Map<String, Integer> m = new HashMap<>();
        values.forEach(v -> m.merge(v, 1, Integer::sum));
        return m;
    }
}
