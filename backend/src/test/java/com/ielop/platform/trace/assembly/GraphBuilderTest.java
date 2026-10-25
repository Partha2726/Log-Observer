package com.ielop.platform.trace.assembly;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.ielop.platform.flowdef.FlowDefinition;
import com.ielop.platform.flowdef.FlowDefinitionLoader;
import com.ielop.platform.logsearch.LogHit;
import com.ielop.platform.support.EsFixtures;
import com.ielop.platform.trace.StoredTrace;
import com.ielop.platform.trace.StoredTraces;
import com.ielop.platform.trace.TraceProperties;
import com.ielop.platform.trace.graph.GraphBuilder;
import com.ielop.platform.trace.graph.TraceGraph;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/** {@code graph_version 1} (section 7.6): TRACE-020 to TRACE-024 and the golden JSON of one FAIL_DB_TIMEOUT_APPROVE trace. */
class GraphBuilderTest {

    static final FlowDefinition DEMO = FlowDefinitionLoader.loadDirectory(Path.of("..", "config", "flows")).get(0);
    static final List<LogHit> HITS = Hits.demo();
    static final Path GOLDEN = Path.of("src", "test", "resources", "golden", "graph-fail-db-timeout-approve.json");
    final Assembler assembler = new Assembler(TraceProperties.defaults());
    final GraphBuilder builder = new GraphBuilder(GraphBuilder.DEFAULT_MAX_NODES_PER_STAGE);

    StoredTrace stored(String ref) {
        return StoredTraces.of(assembler.assemble(ref, DEMO, new InMemoryEvidenceSource(HITS)), Instant.parse("2026-05-16T00:00:00Z"));
    }

    static List<String> refs() {
        return EsFixtures.demoFlows().stream().map(f -> f.get("flowRef").asText()).toList();
    }

    static String ref(String scenario) {
        return EsFixtures.demoFlows().stream().filter(f -> f.get("scenario").asText().equals(scenario))
                .map(f -> f.get("flowRef").asText()).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------ TRACE-020

    @Test
    void everyNodeButPlaceholdersAndEveryEdgeHasEvidence() {
        for (String ref : refs()) {
            StoredTrace s = stored(ref);
            TraceGraph g = builder.build(s, DEMO);
            assertThat(g.graphVersion()).isEqualTo(1);
            for (TraceGraph.Node n : g.nodes()) {
                if (n.kind().equals("PLACEHOLDER")) {
                    assertThat(n.evidenceIds()).isEmpty();
                    assertThat(n.status()).isEqualTo("NOT_REACHED");
                } else {
                    assertThat(n.evidenceIds()).as(ref + " " + n.id()).isNotEmpty();
                }
            }
            for (TraceGraph.Edge e : g.edges()) {
                assertThat(e.evidenceIds()).as(ref + " " + e.id()).isNotEmpty();
            }
            // Every linked line belongs to exactly one non-EXTERNAL node.
            List<String> evidence = g.nodes().stream().filter(n -> !n.kind().equals("EXTERNAL"))
                    .flatMap(n -> n.evidenceIds().stream()).toList();
            assertThat(evidence).doesNotHaveDuplicates()
                    .containsExactlyInAnyOrderElementsOf(s.links().stream().map(l -> l.getEsDocId()).toList());
        }
    }

    // ------------------------------------------------------------ TRACE-021

    @Test
    void exactlyOneFirstFailureNodeWhenFailedAndNoneOtherwise() {
        for (String ref : refs()) {
            StoredTrace s = stored(ref);
            TraceGraph g = builder.build(s, DEMO);
            List<TraceGraph.Node> marked = g.nodes().stream().filter(TraceGraph.Node::isFirstFailure).toList();
            if ("FAILED".equals(g.overallStatus())) {
                assertThat(marked).as(ref).hasSize(1);
                TraceGraph.Node n = marked.get(0);
                assertThat(n.evidenceIds()).contains(s.trace().getFirstFailureDocId());
                assertThat(n.stage()).isEqualTo(s.trace().getFirstFailureStage());
                assertThat(n.status()).isEqualTo("FAILED");
                String component = n.logger() != null ? n.logger() : n.service();
                assertThat(component).isEqualTo(s.trace().getFirstFailureComponent());
            } else {
                assertThat(marked).as(ref).isEmpty();
            }
        }
    }

    // ------------------------------------------------------------ TRACE-022

    @Test
    void handoffsConnectOnlyConsecutiveReachedStagesInDefinitionOrder() {
        List<String> order = DEMO.stages().stream().map(st -> st.name()).toList();
        for (String ref : refs()) {
            TraceGraph g = builder.build(stored(ref), DEMO);
            Map<String, String> stageOf = g.nodes().stream().collect(Collectors.toMap(TraceGraph.Node::id, TraceGraph.Node::stage));
            List<String> reached = g.lanes().stream().filter(l -> !l.status().equals("NOT_REACHED")).map(TraceGraph.Lane::stage).toList();
            List<TraceGraph.Edge> handoffs = g.edges().stream().filter(e -> e.kind().equals("handoff")).toList();
            assertThat(handoffs).hasSize(Math.max(0, reached.size() - 1));
            for (TraceGraph.Edge e : handoffs) {
                int from = reached.indexOf(stageOf.get(e.source()));
                int to = reached.indexOf(stageOf.get(e.target()));
                assertThat(to).as(ref + " " + e.id()).isEqualTo(from + 1);
                assertThat(order.indexOf(stageOf.get(e.target()))).isGreaterThan(order.indexOf(stageOf.get(e.source())));
            }
        }
    }

    // ------------------------------------------------------------ TRACE-023

    @Test
    void componentNodesAreCappedPerLaneAndTheMergedNodeKeepsAllEvidence() {
        String ref = ref("NORMAL");
        StoredTrace s = stored(ref);
        TraceGraph full = new GraphBuilder(100).build(s, DEMO);
        TraceGraph capped = new GraphBuilder(2).build(s, DEMO);
        boolean merged = false;
        for (TraceGraph.Lane lane : full.lanes()) {
            List<TraceGraph.Node> fullComponents = components(full, lane.stage());
            List<TraceGraph.Node> cappedComponents = components(capped, lane.stage());
            assertThat(cappedComponents.size()).isLessThanOrEqualTo(2);
            Set<String> fullEvidence = fullComponents.stream().flatMap(n -> n.evidenceIds().stream()).collect(Collectors.toSet());
            Set<String> cappedEvidence = cappedComponents.stream().flatMap(n -> n.evidenceIds().stream()).collect(Collectors.toSet());
            assertThat(cappedEvidence).isEqualTo(fullEvidence);
            if (fullComponents.size() > 2) {
                merged = true;
                assertThat(cappedComponents).anyMatch(n -> n.label().equals((fullComponents.size() - 1) + " more components"));
            }
        }
        assertThat(merged).as("the NORMAL flow has a lane with more than two components").isTrue();
    }

    private static List<TraceGraph.Node> components(TraceGraph g, String stage) {
        return g.nodes().stream().filter(n -> n.stage().equals(stage) && n.kind().equals("COMPONENT")).toList();
    }

    // ------------------------------------------------------------ TRACE-024 and determinism

    @Test
    void theGraphIsDerivedFromStoredRowsAloneAndIsDeterministic() {
        for (String ref : refs()) {
            StoredTrace s = stored(ref);
            TraceGraph a = builder.build(s, DEMO);
            TraceGraph b = builder.build(stored(ref), DEMO);
            assertThat(b).isEqualTo(a);
            Set<String> ids = new HashSet<>();
            for (TraceGraph.Node n : a.nodes()) {
                assertThat(ids.add(n.id())).isTrue();
                assertThat(n.id()).matches("n-" + n.stage() + "-\\d{2}");
            }
            for (TraceGraph.Edge e : a.edges()) {
                assertThat(e.id()).matches("e-\\d{2}");
                assertThat(ids).contains(e.source(), e.target());
            }
        }
    }

    @Test
    void notReachedStagesHaveAPlaceholder() {
        TraceGraph g = builder.build(stored(ref("FAIL_DB_DOWN_CREATE")), DEMO);
        assertThat(g.nodes()).filteredOn(n -> n.kind().equals("PLACEHOLDER")).extracting(TraceGraph.Node::id)
                .containsExactly("n-Approve-00", "n-Execute-00");
        assertThat(g.lanes()).extracting(TraceGraph.Lane::status).containsExactly("COMPLETED", "FAILED", "NOT_REACHED", "NOT_REACHED");
    }

    // ------------------------------------------------------------ golden JSON

    @Test
    void failDbTimeoutApproveGraphMatchesTheGoldenJson() throws Exception {
        TraceGraph g = builder.build(stored(ref("FAIL_DB_TIMEOUT_APPROVE")), DEMO);
        ObjectMapper json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        String actual = json.writeValueAsString(g) + "\n";
        if (!Files.exists(GOLDEN) || Boolean.getBoolean("updateGolden")) {
            Files.createDirectories(GOLDEN.getParent());
            Files.writeString(GOLDEN, actual, StandardCharsets.UTF_8);
            if (!Boolean.getBoolean("updateGolden")) {
                fail("Golden file written to " + GOLDEN + "; review it and run again");
            }
        }
        assertThat(actual).isEqualTo(Files.readString(GOLDEN, StandardCharsets.UTF_8));
        List<String> kinds = new ArrayList<>(g.nodes().stream().map(TraceGraph.Node::kind).collect(Collectors.toCollection(java.util.TreeSet::new)));
        assertThat(kinds).contains("ACTOR", "SERVICE", "COMPONENT", "PLACEHOLDER");
    }
}
