package com.ielop.platform.detection;

import com.ielop.platform.detection.RuleInput.LineFact;
import com.ielop.platform.detection.RuleInput.StageFact;
import com.ielop.platform.detection.RuleInput.TraceFacts;
import com.ielop.platform.detection.rules.R01SelfApproval;
import com.ielop.platform.detection.rules.R02ApproverIsExecutor;
import com.ielop.platform.detection.rules.R03ExecutionWithoutApproval;
import com.ielop.platform.detection.rules.R04StageOrder;
import com.ielop.platform.detection.rules.R05StageFailed;
import com.ielop.platform.detection.rules.R09SlowStage;
import com.ielop.platform.detection.rules.R10ApprovalAboveLimit;
import com.ielop.platform.detection.rules.R11DuplicateSubmission;
import com.ielop.platform.detection.rules.R12OffHours;
import com.ielop.platform.detection.rules.R13IntegrationFailure;
import com.ielop.platform.flowdef.FlowDefinition;
import com.ielop.platform.flowdef.FlowDefinitionLoader;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Positive and negative case of every trace-scoped rule from a hand-written
 * {@link RuleInput} (T20: RULE-001…005, RULE-009…013). Stage and role names come from
 * the demo flow definition; the rules themselves only know positions.
 */
class TraceRulesTest {

    static final FlowDefinition DEMO = FlowDefinitionLoader.loadDirectory(Path.of("..", "config", "flows")).get(0);
    static final Instant T0 = Instant.parse("2026-05-15T10:00:00Z");

    /** A hand-built trace of the demo flow; every stage completes by default, one minute apart. */
    static final class TraceBuilder {
        String ref = "REQ-20260515-000101";
        long id = 101;
        BigDecimal amount = new BigDecimal("1200.00");
        String counterparty = "cp-hash-1";
        Map<String, String> actors = new LinkedHashMap<>(Map.of("requester", "usr-1042", "approver", "usr-2001", "executor", "usr-3001"));
        Map<String, String> status = new LinkedHashMap<>();
        Map<String, Instant> start = new LinkedHashMap<>();
        Map<String, Duration> length = new LinkedHashMap<>();
        List<LineFact> lines = new ArrayList<>();
        String firstFailureDoc;

        TraceBuilder() {
            int i = 0;
            for (var s : DEMO.stages()) {
                status.put(s.name(), "COMPLETED");
                start.put(s.name(), T0.plus(Duration.ofMinutes(i++)));
                length.put(s.name(), Duration.ofSeconds(5));
            }
            line("SignIn", "auth-service", "LOGIN_OK", "usr-1042");
            line("Create", "workflow-service", "request.created", "usr-1042");
            line("Approve", "workflow-service", "request.approved", "usr-2001");
            line("Execute", "workflow-service", "execution.started", "usr-3001");
            line("Execute", "workflow-service", "execution.completed", "usr-3001");
        }

        TraceBuilder line(String stage, String service, String action, String user) {
            return add(new LineFact("doc-" + (lines.size() + 1), stage, start.get(stage).plusMillis(lines.size()), service,
                    "c.a.w.Handler", action, user, null, null, null, List.of(), false, true));
        }

        TraceBuilder add(LineFact l) {
            lines.add(l);
            return this;
        }

        TraceBuilder stage(String name, String st) {
            status.put(name, st);
            return this;
        }

        TraceFacts build() {
            List<StageFact> stages = new ArrayList<>();
            for (var s : DEMO.stages()) {
                boolean reached = !"NOT_REACHED".equals(status.get(s.name()));
                Instant from = start.get(s.name());
                stages.add(new StageFact(s.name(), s.order(), status.get(s.name()), reached ? from : null,
                        reached ? from.plus(length.get(s.name())) : null));
            }
            List<LineFact> kept = lines.stream().filter(l -> !"NOT_REACHED".equals(status.get(l.stage()))).toList();
            return new TraceFacts(id, ref, DEMO.id(), T0, T0.plus(Duration.ofMinutes(10)), amount, "USD", counterparty,
                    actors, stages, kept, firstFailureDoc);
        }
    }

    private static List<Finding> run(AnomalyRule rule, TraceBuilder b, FakeRuleContext ctx) {
        return rule.evaluate(RuleInput.forTrace(DEMO, b.build(), ctx));
    }

    private static List<Finding> run(AnomalyRule rule, TraceBuilder b) {
        return run(rule, b, new FakeRuleContext());
    }

    private static List<String> docsOf(TraceBuilder b, String stage) {
        return b.build().linesOf(stage).stream().map(LineFact::docId).toList();
    }

    @Test
    void rule001SelfApprovalFiresWhenTheRequesterApprovesTheirOwnRequest() {
        TraceBuilder self = new TraceBuilder();
        self.actors.put("approver", "usr-1042");
        List<Finding> f = run(new R01SelfApproval(), self);
        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.ruleId()).isEqualTo("R01");
            assertThat(x.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(x.userId()).isEqualTo("usr-1042");
            assertThat(x.dedupKey()).isEqualTo("R01:REQ-20260515-000101");
            assertThat(x.evidenceDocIds()).containsExactly("doc-2", "doc-3");
        });

        assertThat(run(new R01SelfApproval(), new TraceBuilder())).isEmpty();
        TraceBuilder rejected = new TraceBuilder().stage("Approve", "REJECTED");
        rejected.actors.put("approver", "usr-1042");
        assertThat(run(new R01SelfApproval(), rejected)).as("no approval completed").isEmpty();
    }

    @Test
    void rule002ApproverIsExecutorFiresOnlyWhenTheSameUserApprovesAndExecutes() {
        TraceBuilder same = new TraceBuilder();
        same.actors.put("executor", "usr-2001");
        assertThat(run(new R02ApproverIsExecutor(), same)).singleElement()
                .satisfies(x -> assertThat(x.evidenceDocIds()).containsExactly("doc-3", "doc-4"));
        assertThat(run(new R02ApproverIsExecutor(), new TraceBuilder())).isEmpty();
        assertThat(new FakeRuleContext().rules().enabled("R02")).as("R02 is disabled by default").isFalse();
    }

    @Test
    void rule003ExecutionWithoutApprovalFiresWhenTheFinalStageRunsAfterAFailedOrMissingApproval() {
        TraceBuilder missing = new TraceBuilder().stage("Approve", "NOT_REACHED");
        assertThat(run(new R03ExecutionWithoutApproval(), missing)).singleElement().satisfies(x -> {
            assertThat(x.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(x.userId()).isEqualTo("usr-3001");
            assertThat(x.evidenceDocIds()).containsExactly(docsOf(missing, "Execute").get(0));
            assertThat(x.details()).containsEntry("approvalStatus", "NOT_REACHED");
        });

        TraceBuilder rejectedThenExecuted = new TraceBuilder().stage("Approve", "REJECTED");
        rejectedThenExecuted.lines.set(2, new LineFact("doc-3", "Approve", T0.plusSeconds(120), "workflow-service", "c.a.w.Handler",
                "request.rejected", "usr-2001", null, null, null, List.of(), false, true));
        assertThat(run(new R03ExecutionWithoutApproval(), rejectedThenExecuted)).singleElement()
                .satisfies(x -> assertThat(x.evidenceDocIds()).contains("doc-3"));

        assertThat(run(new R03ExecutionWithoutApproval(), new TraceBuilder())).isEmpty();
        assertThat(run(new R03ExecutionWithoutApproval(), new TraceBuilder().stage("Approve", "REJECTED").stage("Execute", "NOT_REACHED")))
                .as("rejected and never executed").isEmpty();
    }

    @Test
    void rule004StageOrderFiresWhenALaterStageStartsBeforeAnEarlierOne() {
        TraceBuilder swapped = new TraceBuilder();
        swapped.start.put("Execute", T0.plusSeconds(30));
        List<Finding> f = run(new R04StageOrder(), swapped);
        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.details()).containsEntry("stagesStartedEarly", List.of("Execute"));
            assertThat(x.evidenceDocIds()).isNotEmpty();
        });
        assertThat(run(new R04StageOrder(), new TraceBuilder())).isEmpty();
    }

    @Test
    void rule005StageFailedCitesTheFirstFailureAndRanksApprovalAndFinalStagesHigh() {
        TraceBuilder failed = new TraceBuilder().stage("Approve", "FAILED").stage("Execute", "NOT_REACHED");
        failed.add(new LineFact("doc-err", "Approve", T0.plusSeconds(121), "workflow-service", "c.a.b.l.LimitCheck", null, "usr-2001",
                null, "java.sql.SQLTimeoutException", null, List.of(), true, true));
        failed.firstFailureDoc = "doc-err";
        assertThat(run(new R05StageFailed(), failed)).singleElement().satisfies(x -> {
            assertThat(x.severity()).isEqualTo(Severity.HIGH);
            assertThat(x.evidenceDocIds()).containsExactly("doc-err");
            assertThat(x.userId()).isEqualTo("usr-2001");
            assertThat(x.dedupKey()).isEqualTo("R05:REQ-20260515-000101:Approve");
        });

        TraceBuilder createFailed = new TraceBuilder().stage("Create", "FAILED").stage("Approve", "NOT_REACHED").stage("Execute", "NOT_REACHED");
        createFailed.add(new LineFact("doc-400", "Create", T0.plusSeconds(61), "gateway", null, null, "usr-1042", 400, null, null,
                List.of(), false, true));
        assertThat(run(new R05StageFailed(), createFailed)).singleElement()
                .satisfies(x -> assertThat(x.severity()).isEqualTo(Severity.MEDIUM));

        assertThat(run(new R05StageFailed(), new TraceBuilder())).isEmpty();
    }

    @Test
    void rule009SlowStageUsesTheLargerOfTheP99AndTheFloorAndSkipsSessionStages() {
        TraceBuilder slow = new TraceBuilder();
        slow.length.put("Approve", Duration.ofMinutes(30));
        slow.length.put("SignIn", Duration.ofHours(2));
        FakeRuleContext ctx = new FakeRuleContext();
        ctx.p99 = 60_000L;
        assertThat(run(new R09SlowStage(), slow, ctx)).singleElement().satisfies(x -> {
            assertThat(x.details()).containsEntry("stage", "Approve").containsEntry("thresholdMs", 60_000L);
            assertThat(x.evidenceDocIds()).containsExactly("doc-3");
        });

        TraceBuilder belowFloor = new TraceBuilder();
        belowFloor.length.put("Approve", Duration.ofSeconds(10));
        FakeRuleContext tiny = new FakeRuleContext();
        tiny.p99 = 1_000L;
        assertThat(run(new R09SlowStage(), belowFloor, tiny)).as("the 15 s floor applies").isEmpty();
        assertThat(run(new R09SlowStage(), new TraceBuilder(), ctx)).isEmpty();
        assertThat(run(new R09SlowStage(), slow, new FakeRuleContext())).as("no baseline yet").isEmpty();
    }

    @Test
    void rule010ApprovalAboveLimitComparesTheAmountWithTheApproversLimit() {
        TraceBuilder big = new TraceBuilder();
        big.amount = new BigDecimal("25000.00");
        FakeRuleContext ctx = new FakeRuleContext();
        ctx.limits.put("usr-2001", new BigDecimal("10000.00"));
        assertThat(run(new R10ApprovalAboveLimit(), big, ctx)).singleElement().satisfies(x -> {
            assertThat(x.userId()).isEqualTo("usr-2001");
            assertThat(x.evidenceDocIds()).containsExactly("doc-2", "doc-3");
            assertThat(x.details()).containsEntry("limit", new BigDecimal("10000.00"));
        });

        assertThat(run(new R10ApprovalAboveLimit(), new TraceBuilder(), ctx)).as("within the limit").isEmpty();
        assertThat(run(new R10ApprovalAboveLimit(), big, new FakeRuleContext())).as("no imported limit").isEmpty();
    }

    @Test
    void rule011DuplicateSubmissionPairsTheTwoCreationsWithinTheWindow() {
        FakeRuleContext ctx = new FakeRuleContext();
        ctx.submissions.add(new RuleContext.Submission(77, "REQ-20260515-000100", T0.minusSeconds(20), List.of("other-create")));
        List<Finding> f = run(new R11DuplicateSubmission(), new TraceBuilder(), ctx);
        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.dedupKey()).isEqualTo("R11:REQ-20260515-000100:REQ-20260515-000101");
            assertThat(x.flowRef()).isEqualTo("REQ-20260515-000101");
            assertThat(x.traceId()).isEqualTo(101L);
            assertThat(x.evidenceDocIds()).containsExactly("other-create", "doc-2");
        });

        // Evaluated from the earlier trace, the row still belongs to the later one.
        FakeRuleContext later = new FakeRuleContext();
        later.submissions.add(new RuleContext.Submission(88, "REQ-20260515-000102", T0.plusSeconds(20), List.of("later-create")));
        assertThat(run(new R11DuplicateSubmission(), new TraceBuilder(), later)).singleElement().satisfies(x -> {
            assertThat(x.dedupKey()).isEqualTo("R11:REQ-20260515-000101:REQ-20260515-000102");
            assertThat(x.flowRef()).isEqualTo("REQ-20260515-000102");
            assertThat(x.traceId()).isEqualTo(88L);
        });

        FakeRuleContext far = new FakeRuleContext();
        far.submissions.add(new RuleContext.Submission(77, "REQ-20260515-000100", T0.minusSeconds(600), List.of("other-create")));
        assertThat(run(new R11DuplicateSubmission(), new TraceBuilder(), far)).as("outside 60 s").isEmpty();
    }

    @Test
    void rule012OffHoursFiresForApprovalOrExecutionInTheBusinessNight() {
        TraceBuilder night = new TraceBuilder();
        night.start.put("Approve", Instant.parse("2026-05-15T02:10:00Z"));
        night.start.put("Execute", Instant.parse("2026-05-15T02:20:00Z"));
        night.lines.replaceAll(l -> new LineFact(l.docId(), l.stage(), night.start.get(l.stage()), l.service(), l.logger(),
                l.eventAction(), l.userId(), null, null, null, List.of(), false, true));
        assertThat(run(new R12OffHours(), night)).hasSize(2).first().satisfies(x -> {
            assertThat(x.userId()).isEqualTo("usr-2001");
            assertThat(x.details()).containsEntry("localHour", 2);
        });

        FakeRuleContext otherZone = new FakeRuleContext();
        otherZone.props.setBusinessTimezone("Asia/Kolkata");
        assertThat(run(new R12OffHours(), night, otherZone)).as("02:10 UTC is 07:40 in the business zone").isEmpty();
        assertThat(run(new R12OffHours(), new TraceBuilder())).isEmpty();
    }

    @Test
    void rule013IntegrationFailureNeedsAnExternalIdAndAnErrorOrA5xxProviderStatus() {
        TraceBuilder failed = new TraceBuilder().stage("Execute", "FAILED");
        failed.add(new LineFact("doc-ext", "Execute", T0.plusSeconds(181), "workflow-service", "c.a.i.ErpClient", "execution.failed",
                "usr-3001", null, null, "503", List.of("ERP-7781"), true, true));
        assertThat(run(new R13IntegrationFailure(), failed)).singleElement().satisfies(x -> {
            assertThat(x.evidenceDocIds()).containsExactly("doc-ext");
            assertThat(x.details()).containsEntry("providerStatus", "503");
        });

        TraceBuilder internal = new TraceBuilder().stage("Execute", "FAILED");
        internal.add(new LineFact("doc-int", "Execute", T0.plusSeconds(181), "workflow-service", "c.a.w.Handler", "execution.failed",
                "usr-3001", null, "java.lang.IllegalStateException", null, List.of(), true, true));
        assertThat(run(new R13IntegrationFailure(), internal)).as("no external correlation ID").isEmpty();

        TraceBuilder ok = new TraceBuilder();
        ok.add(new LineFact("doc-ok", "Execute", T0.plusSeconds(181), "workflow-service", "c.a.i.ErpClient", "execution.completed",
                "usr-3001", null, null, "200", List.of("ERP-7782"), false, true));
        assertThat(run(new R13IntegrationFailure(), ok)).isEmpty();
    }

    @Test
    void everyFindingNeedsEvidence() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new Finding("R01", Severity.LOW, "k", 1L, "REQ-20260515-000101",
                null, null, T0, T0, Map.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
