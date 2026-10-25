package com.ielop.platform.trace.assembly;

import com.ielop.platform.flowdef.FlowDefinition;
import com.ielop.platform.flowdef.FlowDefinitionLoader;
import com.ielop.platform.logsearch.LogHit;
import com.ielop.platform.trace.TraceProperties;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static com.ielop.platform.trace.assembly.Hits.hit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Assembly steps 1 to 12 on a hand-built purchase-request flow (TRACE-001 to 009,
 * 016 to 018, 025 to 028). Requester usr-1001 signs in, creates the request and signs
 * out; approver usr-2002 signs in, approves and executes in the same session, then
 * signs out. The legacy billing service writes one line without a request ID.
 */
class AssemblerStepsTest {

    static final String REF = "REQ-20260515-000944";
    static final String OTHER = "REQ-20260515-000945";
    static final FlowDefinition DEMO = FlowDefinitionLoader.loadDirectory(Path.of("..", "config", "flows")).get(0);
    final Assembler assembler = new Assembler(TraceProperties.defaults());

    static List<LogHit> flow() {
        return new ArrayList<>(List.of(
                // requester sign-in (r1)
                hit("a1", "2026-05-15T10:00:00.500Z").service("auth-service").host("auth-01").action("LOGIN_OK").user("usr-1001").session("s1").rid("r1").build(),
                hit("g1", "2026-05-15T10:00:00.900Z").service("gateway").host("gw-01").rid("r1").http("POST", "/auth/login", 200).build(),
                // create (r2)
                hit("w1", "2026-05-15T10:00:10.010Z").service("workflow-service").host("wf-01").thread("http-nio-1").logger("com.acme.workflow.request.RequestService")
                        .ref(REF).user("usr-1001").session("s1").rid("r2").action("request.created").amount("2500.00").build(),
                hit("b1", "2026-05-15T10:00:10.050Z").service("billing-service").host("bill-01").thread("billing-worker-1").logger("c.a.b.b.BudgetService")
                        .rid("r2").action("budget.reserved").build(),
                hit("b2", "2026-05-15T10:00:10.300Z").service("billing-service").host("bill-01").thread("billing-worker-1").logger("c.a.b.b.BudgetDao").build(),
                hit("g2", "2026-05-15T10:00:10.400Z").service("gateway").host("gw-01").user("usr-1001").rid("r2").http("POST", "/api/requests", 201).build(),
                // requester sign-out (r3)
                hit("a2", "2026-05-15T10:01:00.010Z").service("auth-service").host("auth-01").action("LOGOUT").user("usr-1001").session("s1").rid("r3").build(),
                hit("g3", "2026-05-15T10:01:00.030Z").service("gateway").host("gw-01").user("usr-1001").rid("r3").http("POST", "/auth/logout", 204).build(),
                // approver sign-in (r4)
                hit("a3", "2026-05-15T11:00:00.500Z").service("auth-service").host("auth-01").action("LOGIN_OK").user("usr-2002").session("s2").rid("r4").build(),
                hit("g4", "2026-05-15T11:00:00.900Z").service("gateway").host("gw-01").rid("r4").http("POST", "/auth/login", 200).build(),
                // approve (r5)
                hit("a4", "2026-05-15T11:00:10.005Z").service("auth-service").host("auth-01").action("AUTHZ_OK").user("usr-2002").rid("r5").build(),
                hit("w2", "2026-05-15T11:00:10.100Z").service("workflow-service").host("wf-01").thread("http-nio-2").logger("com.acme.workflow.approval.ApprovalService")
                        .ref(REF).user("usr-2002").session("s2").rid("r5").action("request.approved").build(),
                hit("g5", "2026-05-15T11:00:10.200Z").service("gateway").host("gw-01").ref(REF).user("usr-2002").rid("r5")
                        .http("POST", "/api/requests/" + REF + "/approve", 200).build(),
                // execute (r6), same session
                hit("w3", "2026-05-15T11:05:00.100Z").service("workflow-service").host("wf-02").thread("http-nio-3").logger("com.acme.workflow.execution.ExecutionService")
                        .ref(REF).user("usr-2002").session("s2").rid("r6").action("execution.completed").build(),
                hit("g6", "2026-05-15T11:05:00.200Z").service("gateway").host("gw-01").ref(REF).user("usr-2002").rid("r6")
                        .http("POST", "/api/requests/" + REF + "/execute", 200).build(),
                // approver sign-out (r7)
                hit("a5", "2026-05-15T11:06:00.010Z").service("auth-service").host("auth-01").action("LOGOUT").user("usr-2002").session("s2").rid("r7").build(),
                hit("g7", "2026-05-15T11:06:00.030Z").service("gateway").host("gw-01").user("usr-2002").rid("r7").http("POST", "/auth/logout", 204).build()));
    }

    AssemblyResult assemble(List<LogHit> hits) {
        return assembler.assemble(REF, DEMO, new InMemoryEvidenceSource(hits));
    }

    static TraceLink link(AssemblyResult r, String id) {
        return r.links().stream().filter(l -> l.hit().id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError(id + " is not linked"));
    }

    static boolean linked(AssemblyResult r, String id) {
        return r.links().stream().anyMatch(l -> l.hit().id().equals(id));
    }

    static List<LogHit> replace(List<LogHit> hits, String id, UnaryOperator<Hits.Builder> change, String ts) {
        List<LogHit> out = new ArrayList<>(hits);
        out.removeIf(h -> h.id().equals(id));
        if (change != null) {
            out.add(change.apply(hit(id, ts)).build());
        }
        return out;
    }

    static StageStatus status(AssemblyResult r, String stage) {
        return r.stages().stream().filter(s -> s.name().equals(stage)).findFirst().orElseThrow().status();
    }

    // ------------------------------------------------------------------ TRACE-001

    @Test
    void step1SeedLinesAreObservedById() {
        AssemblyResult r = assemble(flow());
        for (String id : List.of("w1", "w2", "g5", "w3", "g6")) {
            TraceLink l = link(r, id);
            assertThat(l.evidenceClass()).isEqualTo(EvidenceClass.OBSERVED);
            assertThat(l.reason()).isEqualTo(LinkReason.ID);
            assertThat(l.confidence()).isEqualByComparingTo("1.00");
        }
        assertThatThrownBy(() -> assembler.assemble("REQ-20260515-999999", DEMO, new InMemoryEvidenceSource(flow())))
                .isInstanceOf(TraceNotFoundException.class);
    }

    // ------------------------------------------------------------------ denied attempts (assembly version 2)

    @Test
    void deniedAttemptsStayAsEvidenceButNeverShapeStagesActorsOrTheSpan() {
        // Only the requester's part happened; hours later another user tries to approve and is refused.
        List<LogHit> hits = new ArrayList<>(flow().subList(0, 8));
        hits.add(hit("p0", "2026-05-15T18:00:00.500Z").service("auth-service").host("auth-01").action("LOGIN_OK").user("usr-3003")
                .session("s9").rid("r8").build());
        hits.add(hit("p1", "2026-05-15T18:00:30.005Z").service("auth-service").host("auth-01").action("AUTHZ_DENIED").ref(REF)
                .user("usr-3003").session("s9").rid("r9").build());
        hits.add(hit("p2", "2026-05-15T18:00:30.020Z").service("gateway").host("gw-01").ref(REF).user("usr-3003").rid("r9")
                .http("POST", "/api/requests/" + REF + "/approve", 403).build());

        AssemblyResult r = assemble(hits);

        assertThat(linked(r, "p1")).isTrue();
        assertThat(linked(r, "p2")).isTrue();
        assertThat(linked(r, "p0")).as("the refused user's session is not expanded").isFalse();
        assertThat(status(r, "Create")).isEqualTo(StageStatus.COMPLETED);
        assertThat(status(r, "Approve")).isEqualTo(StageStatus.NOT_REACHED);
        assertThat(status(r, "Execute")).isEqualTo(StageStatus.NOT_REACHED);
        assertThat(r.actors()).doesNotContainValue("usr-3003");
        assertThat(r.lastSeen()).isEqualTo(java.time.Instant.parse("2026-05-15T10:01:00.030Z"));
        assertThat(r.overallStatus()).isEqualTo("INCOMPLETE");
    }

    // ------------------------------------------------------------------ phase 3: OpenTelemetry trace IDs (T33)

    @Test
    void aSharedTraceIdLinksLegacyLinesAsObservedAndSpansGiveTheirStage() {
        String trace = "4bf92f3577b34da6a3ce929d0e0e4736";
        String createSpan = "00f067aa0ba902b7";
        List<LogHit> hits = replace(flow(), "w1", h -> h.service("workflow-service").host("wf-01").thread("http-nio-1")
                .logger("com.acme.workflow.request.RequestService").ref(REF).user("usr-1001").session("s1").rid("r2")
                .action("request.created").amount("2500.00").trace(trace).span(createSpan), "2026-05-15T10:00:10.010Z");
        // The legacy billing line without request ID, now with the flow's trace and the Create span.
        hits = replace(hits, "b2", h -> h.service("billing-service").host("bill-01").thread("billing-worker-1")
                .logger("c.a.b.b.BudgetDao").trace(trace).span(createSpan), "2026-05-15T10:00:10.300Z");
        // A line on another host and much later, found only through the trace ID.
        hits.add(hit("t9", "2026-05-15T12:00:00.000Z").service("billing-service").host("bill-02").thread("billing-worker-9")
                .logger("c.a.b.n.Notifier").trace(trace).span(createSpan).build());
        // Another flow's line on the same thread: a different trace, never linked through it.
        hits.add(hit("x1", "2026-05-15T10:00:10.320Z").service("billing-service").host("bill-01").thread("billing-worker-1")
                .logger("c.a.b.b.BudgetDao").trace("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").span("bbbbbbbbbbbbbbbb").build());

        AssemblyResult r = assemble(hits);

        TraceLink legacy = link(r, "b2");
        assertThat(legacy.evidenceClass()).isEqualTo(EvidenceClass.OBSERVED);
        assertThat(legacy.reason()).isEqualTo(LinkReason.TRACE);
        assertThat(legacy.confidence()).isEqualByComparingTo("1.00");
        TraceLink far = link(r, "t9");
        assertThat(far.reason()).isEqualTo(LinkReason.TRACE);
        assertThat(far.stage()).as("stage from the span").isEqualTo("Create");
        assertThat(linked(r, "x1") && link(r, "x1").reason() == LinkReason.TRACE).as("another trace is not a key for this flow").isFalse();

        // Without trace IDs the same legacy line is CORRELATED by thread (phase 1 behaviour).
        assertThat(link(assemble(flow()), "b2").evidenceClass()).isEqualTo(EvidenceClass.CORRELATED);
    }

    // ------------------------------------------------------------------ TRACE-002, TRACE-028

    @Test
    void step2RequestExpansionLinksEveryLineOfTheFlowsCalls() {
        AssemblyResult r = assemble(flow());
        for (String id : List.of("b1", "g2", "a4")) {
            assertThat(link(r, id).reason()).isEqualTo(LinkReason.REQUEST);
            assertThat(link(r, id).evidenceClass()).isEqualTo(EvidenceClass.OBSERVED);
        }
    }

    @Test
    void requestIdsAreExpandedOneHopOnly() {
        // a4 (found through r5) carries a session; a line of an unrelated call that shares only that
        // session's non-session event is not reached, and no request ID found on a REQUEST line is queried.
        List<String> queried = new ArrayList<>();
        List<LogHit> hits = flow();
        hits.add(hit("z1", "2026-05-15T11:30:00.000Z").service("workflow-service").host("wf-01").thread("http-nio-9")
                .user("usr-2002").session("s2").rid("r99").build());
        InMemoryEvidenceSource base = new InMemoryEvidenceSource(hits);
        TraceEvidenceSource recording = new DelegatingSource(base) {
            @Override
            public List<LogHit> byRequestIds(Collection<String> requestIds) {
                queried.addAll(requestIds);
                return super.byRequestIds(requestIds);
            }
        };
        AssemblyResult r = assembler.assemble(REF, DEMO, recording);

        assertThat(queried).containsExactlyInAnyOrder("r2", "r5", "r6", "r1", "r3", "r4", "r7");
        assertThat(linked(r, "z1")).isFalse();
    }

    // ------------------------------------------------------------------ TRACE-003

    @Test
    void step3SessionExpansionLinksEachActorsSignInAndSignOut() {
        AssemblyResult r = assemble(flow());
        for (String id : List.of("a1", "a2", "a3", "a5", "g1", "g3", "g4", "g7")) {
            assertThat(link(r, id).reason()).as(id).isEqualTo(LinkReason.SESSION);
        }
    }

    @Test
    void step3FallbackUsesTheActorsLastSignInBeforeTheirFirstLine() {
        // The approver's workflow lines lost their session ID: no session match for usr-2002.
        List<LogHit> hits = replace(flow(), "w2", b -> b.service("workflow-service").host("wf-01").thread("http-nio-2")
                .ref(REF).user("usr-2002").rid("r5").action("request.approved"), "2026-05-15T11:00:10.100Z");
        hits = replace(hits, "w3", b -> b.service("workflow-service").host("wf-02").thread("http-nio-3")
                .ref(REF).user("usr-2002").rid("r6").action("execution.completed"), "2026-05-15T11:05:00.100Z");
        AssemblyResult r = assemble(hits);

        TraceLink fallback = link(r, "a3");
        assertThat(fallback.reason()).isEqualTo(LinkReason.SESSION_FALLBACK);
        assertThat(fallback.evidenceClass()).isEqualTo(EvidenceClass.CORRELATED);
        assertThat(fallback.confidence()).isEqualByComparingTo("0.70");
        assertThat(fallback.stage()).isEqualTo("SignIn");
    }

    // ------------------------------------------------------------------ TRACE-004, TRACE-005

    @Test
    void step4StagesComeFromMatchersThenRequestIdThenWindows() {
        List<LogHit> hits = flow();
        // Carries the flowRef in its text, no request ID, no matcher: falls in the Approve window.
        hits.add(hit("b3", "2026-05-15T11:00:10.150Z").service("billing-service").host("bill-01").thread("billing-worker-2")
                .ref(REF).logger("c.a.b.l.LimitCheck").build());
        AssemblyResult r = assemble(hits);

        assertThat(link(r, "g1").stage()).isEqualTo("SignIn");
        assertThat(link(r, "a3").stage()).isEqualTo("SignIn");
        assertThat(link(r, "w1").stage()).isEqualTo("Create");
        assertThat(link(r, "b1").stage()).as("inherits Create through request r2").isEqualTo("Create");
        assertThat(link(r, "a4").stage()).isEqualTo("Approve");
        assertThat(link(r, "g6").stage()).isEqualTo("Execute");
        assertThat(link(r, "a2").stage()).isEqualTo(FlowDefinition.SIGN_OUT);
        assertThat(link(r, "g7").stage()).isEqualTo(FlowDefinition.SIGN_OUT);
        assertThat(link(r, "b3").stage()).as("stage window").isEqualTo("Approve");
        assertThat(link(r, "b2").stage()).as("CORRELATED line takes its seed's stage").isEqualTo("Create");
    }

    // ------------------------------------------------------------------ TRACE-006, TRACE-016

    @Test
    void step6ThreadExpansionLinksLinesWithoutIdsOnTheSameHostAndThread() {
        List<LogHit> hits = flow();
        hits.add(hit("b4", "2026-05-15T10:00:10.750Z").service("billing-service").host("bill-01").thread("billing-worker-1").build());
        hits.add(hit("b5", "2026-05-15T10:00:10.200Z").service("billing-service").host("bill-02").thread("billing-worker-1").build());
        hits.add(hit("b6", "2026-05-15T10:00:10.250Z").service("billing-service").host("bill-01").thread("billing-worker-1").rid("r-other").build());
        AssemblyResult r = assemble(hits);

        TraceLink b2 = link(r, "b2");
        assertThat(b2.evidenceClass()).isEqualTo(EvidenceClass.CORRELATED);
        assertThat(b2.reason()).isEqualTo(LinkReason.THREAD);
        assertThat(b2.confidence()).isEqualByComparingTo("0.80");
        assertThat(linked(r, "b4")).as("outside the 500 ms window").isFalse();
        assertThat(linked(r, "b5")).as("other host").isFalse();
        assertThat(linked(r, "b6")).as("another call's request ID").isFalse();
    }

    @Test
    void competingFlowOnTheThreadLowersConfidenceToHalf() {
        List<LogHit> hits = flow();
        hits.add(hit("x1", "2026-05-15T10:00:10.350Z").service("billing-service").host("bill-01").thread("billing-worker-1").rid("rX").build());
        hits.add(hit("x2", "2026-05-15T10:00:09.000Z").service("workflow-service").host("wf-02").thread("http-nio-7").ref(OTHER).rid("rX").build());
        AssemblyResult r = assemble(hits);

        assertThat(link(r, "b2").confidence()).isEqualByComparingTo("0.50");
        assertThat(linked(r, "x1")).isFalse();
    }

    // ------------------------------------------------------------------ TRACE-007, TRACE-017

    @Test
    void step7TimeOnlyLinksIdlessThreadlessLinesNearAnObservedLine() {
        List<LogHit> hits = flow();
        hits.add(hit("t1", "2026-05-15T10:00:00.600Z").service("auth-service").host("auth-01").build());
        AssemblyResult r = assemble(hits);

        TraceLink t1 = link(r, "t1");
        assertThat(t1.reason()).isEqualTo(LinkReason.TIME);
        assertThat(t1.confidence()).isEqualByComparingTo("0.60");
        assertThat(t1.stage()).isEqualTo("SignIn");
    }

    @Test
    void timeOnlyConfidenceIsSplitBetweenFlowsInTheWindow() {
        List<LogHit> two = flow();
        two.add(hit("t1", "2026-05-15T10:00:00.600Z").service("auth-service").host("auth-01").build());
        two.add(hit("o1", "2026-05-15T10:00:00.700Z").service("auth-service").host("auth-01").ref(OTHER).user("usr-3003").build());
        assertThat(link(assemble(two), "t1").confidence()).isEqualByComparingTo("0.30");

        List<LogHit> three = new ArrayList<>(two);
        three.add(hit("o2", "2026-05-15T10:00:00.650Z").service("auth-service").host("auth-01").ref("REQ-20260515-000946").user("usr-4004").build());
        AssemblyResult r = assemble(three);
        assertThat(linked(r, "t1")).as("0.6 / 3 = 0.2 is below minConfidence 0.3").isFalse();
        assertThat(r.excludedCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ TRACE-008

    @Test
    void step8NoiseThreadsAreNeverCorrelated() {
        List<LogHit> hits = flow();
        hits.add(hit("s1", "2026-05-15T10:00:10.100Z").service("billing-service").host("bill-01").thread("scheduling-1").ref(REF).build());
        hits.add(hit("s2", "2026-05-15T10:00:10.200Z").service("billing-service").host("bill-01").thread("scheduling-1").build());
        AssemblyResult r = assemble(hits);

        assertThat(link(r, "s1").evidenceClass()).as("OBSERVED lines on noise threads stay").isEqualTo(EvidenceClass.OBSERVED);
        assertThat(linked(r, "s2")).isFalse();
    }

    // ------------------------------------------------------------------ TRACE-009

    @Test
    void step9ActorsAreTheUsersOnEachActorStagesObservedLines() {
        List<LogHit> hits = flow();
        hits.add(hit("b9", "2026-05-15T11:00:10.120Z").service("billing-service").host("bill-01").thread("billing-worker-3")
                .rid("r5").user("usr-9009").build());
        AssemblyResult r = assemble(hits);

        assertThat(r.actors()).containsExactly(Map.entry("requester", "usr-1001"), Map.entry("approver", "usr-2002"),
                Map.entry("executor", "usr-2002"));
        assertThat(r.stages()).extracting(StageResult::userId).containsExactly("usr-1001", "usr-1001", "usr-2002", "usr-2002");
    }

    // ------------------------------------------------------------------ TRACE-025, TRACE-018

    @Test
    void step10AllStagesCompletedForTheNormalFlow() {
        AssemblyResult r = assemble(flow());
        assertThat(r.stages()).extracting(StageResult::status).containsOnly(StageStatus.COMPLETED);
        assertThat(r.overallStatus()).isEqualTo("COMPLETED");
        assertThat(r.firstFailure()).isNull();
        assertThat(r.amount()).isEqualByComparingTo("2500.00");
    }

    @Test
    void missingSuccessMarkerWithALaterStageIsInferredCompleted() {
        List<LogHit> hits = replace(flow(), "w2", b -> b.service("workflow-service").host("wf-01").thread("http-nio-2")
                .ref(REF).user("usr-2002").session("s2").rid("r5").action("request.approval_started"), "2026-05-15T11:00:10.100Z");
        assertThat(status(assemble(hits), "Approve")).isEqualTo(StageStatus.COMPLETED);
    }

    @Test
    void stageStatusRules() {
        // FAILED by an error line; Execute then NOT_REACHED.
        List<LogHit> failed = replace(flow(), "g5", b -> b.service("gateway").host("gw-01").ref(REF).user("usr-2002").rid("r5")
                .http("POST", "/api/requests/" + REF + "/approve", 500), "2026-05-15T11:00:40.200Z");
        failed.removeIf(h -> List.of("w2", "w3", "g6").contains(h.id()));
        failed.add(hit("w2", "2026-05-15T11:00:10.100Z").service("workflow-service").host("wf-01").thread("http-nio-2")
                .ref(REF).user("usr-2002").session("s2").rid("r5").action("request.approval_started").build());
        AssemblyResult r = assemble(failed);
        assertThat(status(r, "Approve")).isEqualTo(StageStatus.FAILED);
        assertThat(status(r, "Execute")).isEqualTo(StageStatus.NOT_REACHED);
        assertThat(r.overallStatus()).isEqualTo("FAILED");

        // FAILED by a 4xx gateway response without a success event (validation).
        List<LogHit> invalid = new ArrayList<>(flow().subList(0, 2));
        invalid.add(hit("w1", "2026-05-15T10:00:10.010Z").service("workflow-service").host("wf-01").thread("http-nio-1")
                .ref(REF).user("usr-1001").session("s1").rid("r2").action("request.validation_failed").warn().build());
        invalid.add(hit("g2", "2026-05-15T10:00:10.400Z").service("gateway").host("gw-01").user("usr-1001").rid("r2").http("POST", "/api/requests", 422).build());
        AssemblyResult v = assemble(invalid);
        assertThat(status(v, "Create")).isEqualTo(StageStatus.FAILED);
        assertThat(v.firstFailure().component()).isEqualTo("gateway");
        assertThat(v.firstFailure().httpStatus()).isEqualTo(422);

        // REJECTED on a canReject stage.
        List<LogHit> rejected = replace(flow(), "w2", b -> b.service("workflow-service").host("wf-01").thread("http-nio-2")
                .ref(REF).user("usr-2002").session("s2").rid("r5").action("request.rejected"), "2026-05-15T11:00:10.100Z");
        rejected.removeIf(h -> List.of("w3", "g6").contains(h.id()));
        AssemblyResult j = assemble(rejected);
        assertThat(status(j, "Approve")).isEqualTo(StageStatus.REJECTED);
        assertThat(j.overallStatus()).isEqualTo("REJECTED");

        // INCOMPLETE: lines but no success and nothing later.
        List<LogHit> incomplete = new ArrayList<>(flow().subList(0, 2));
        incomplete.add(hit("w1", "2026-05-15T10:00:10.010Z").service("workflow-service").host("wf-01").thread("http-nio-1")
                .ref(REF).user("usr-1001").session("s1").rid("r2").action("request.form_opened").build());
        assertThat(status(assemble(incomplete), "Create")).isEqualTo(StageStatus.INCOMPLETE);
        assertThat(assemble(incomplete).overallStatus()).isEqualTo("INCOMPLETE");
    }

    // ------------------------------------------------------------------ TRACE-026

    @Test
    void step11FirstFailureIsTheEarliestErrorLineIncludingUnambiguousCorrelatedOnes() {
        List<LogHit> hits = flow();
        hits.removeIf(h -> List.of("w2", "g5", "w3", "g6").contains(h.id()));
        hits.add(hit("b7", "2026-05-15T11:00:10.050Z").service("billing-service").host("bill-01").thread("billing-worker-4")
                .rid("r5").logger("c.a.b.l.LimitDao").build());
        hits.add(hit("b8", "2026-05-15T11:00:10.300Z").service("billing-service").host("bill-01").thread("billing-worker-4")
                .logger("c.a.b.l.LimitCheck").error("java.sql.SQLTimeoutException").build());
        hits.add(hit("w2", "2026-05-15T11:00:10.400Z").service("workflow-service").host("wf-01").thread("http-nio-2")
                .ref(REF).user("usr-2002").session("s2").rid("r5").logger("com.acme.workflow.approval.ApprovalService").errorLevel().build());
        hits.add(hit("g5", "2026-05-15T11:00:10.500Z").service("gateway").host("gw-01").ref(REF).user("usr-2002").rid("r5")
                .http("POST", "/api/requests/" + REF + "/approve", 500).build());
        AssemblyResult r = assemble(hits);

        assertThat(link(r, "b8").evidenceClass()).isEqualTo(EvidenceClass.CORRELATED);
        assertThat(r.firstFailure().stage()).isEqualTo("Approve");
        assertThat(r.firstFailure().component()).isEqualTo("c.a.b.l.LimitCheck");
        assertThat(r.firstFailure().exception()).isEqualTo("java.sql.SQLTimeoutException");
        assertThat(r.firstFailure().docId()).isEqualTo("b8");
        // TRACE-014: the status does not depend on the CORRELATED line.
        assertThat(status(r, "Approve")).isEqualTo(StageStatus.FAILED);
    }

    // ------------------------------------------------------------------ TRACE-027

    @Test
    void step12OrderIsTimestampThenFileThenOffset() {
        List<LogHit> hits = flow();
        hits.add(hit("same-b", "2026-05-15T10:00:10.010Z").service("workflow-service").host("wf-01").thread("http-nio-1")
                .ref(REF).rid("r2").file("/logs/workflow-service/b.json").build());
        hits.add(hit("same-a", "2026-05-15T10:00:10.010Z").service("workflow-service").host("wf-01").thread("http-nio-1")
                .ref(REF).rid("r2").file("/logs/workflow-service/a.json").build());
        AssemblyResult r = assemble(hits);

        for (int i = 0; i < r.links().size(); i++) {
            assertThat(r.links().get(i).seq()).isEqualTo(i);
            if (i > 0) {
                assertThat(Assembler.ORDER.compare(r.links().get(i - 1).hit(), r.links().get(i).hit())).isNegative();
            }
        }
        assertThat(link(r, "same-a").seq()).isLessThan(link(r, "same-b").seq());
        assertThat(r.observedRatio()).isEqualByComparingTo(
                BigDecimal.valueOf(r.links().stream().filter(l -> l.evidenceClass() == EvidenceClass.OBSERVED).count())
                        .divide(BigDecimal.valueOf(r.links().size()), 2, java.math.RoundingMode.HALF_UP));
    }

    /** Pass-through source for recording calls. */
    static class DelegatingSource implements TraceEvidenceSource {
        private final TraceEvidenceSource d;

        DelegatingSource(TraceEvidenceSource d) {
            this.d = d;
        }

        public com.ielop.platform.logsearch.HitList seed(String flowRef) { return d.seed(flowRef); }
        public List<LogHit> byRequestIds(Collection<String> ids) { return d.byRequestIds(ids); }
        public List<LogHit> sessionEvents(Collection<String> s, Collection<String> a) { return d.sessionEvents(s, a); }
        public java.util.Optional<LogHit> lastEventBefore(String u, Collection<String> a, java.time.Instant b) { return d.lastEventBefore(u, a, b); }
        public List<LogHit> onThread(String h, String t, List<com.ielop.platform.logsearch.LogCriteria.TimeRange> r) { return d.onThread(h, t, r); }
        public List<LogHit> onHost(String h, List<com.ielop.platform.logsearch.LogCriteria.TimeRange> r) { return d.onHost(h, r); }
        public Map<String, java.util.Set<String>> flowRefsByRequestId(Collection<String> ids) { return d.flowRefsByRequestId(ids); }
    }
}
