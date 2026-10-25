package com.ielop.platform.detection;

import com.ielop.platform.detection.rules.R06StuckFlow;
import com.ielop.platform.detection.rules.R07SignInBruteForce;
import com.ielop.platform.detection.rules.R08ConcurrentSessions;
import com.ielop.platform.detection.rules.R14AuthorizationProbing;
import com.ielop.platform.detection.rules.R15ScannerTraffic;
import com.ielop.platform.logsearch.LogHit;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.ielop.platform.detection.FakeRuleContext.hit;
import static org.assertj.core.api.Assertions.assertThat;

/** Positive and negative case of every scan rule from hand-written log lines (T21: RULE-006…008, RULE-014, RULE-015). */
class ScanRulesTest {

    static final Instant FROM = Instant.parse("2026-05-15T00:00:00Z");
    static final Instant TO = Instant.parse("2026-05-16T00:00:00Z");

    private static List<Finding> scan(AnomalyRule rule, FakeRuleContext ctx) {
        return rule.evaluate(RuleInput.forWindow(FROM, TO, ctx));
    }

    private static Instant at(String hhmmss) {
        return Instant.parse("2026-05-15T" + hhmmss + "Z");
    }

    @Test
    void rule006StuckFlowReportsCreatedButUnapprovedFlowsOlderThanTheCutoff() {
        FakeRuleContext ctx = new FakeRuleContext();
        ctx.stuck.add(new RuleContext.StuckTrace(7, "REQ-20260515-000007", "usr-1042", at("09:00:00"), "FAILED", List.of("doc-created")));
        ctx.stuck.add(new RuleContext.StuckTrace(8, "REQ-20260515-000008", "usr-1042", at("22:30:00"), "NOT_REACHED", List.of("doc-late")));
        List<Finding> f = scan(new R06StuckFlow(), ctx);
        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.dedupKey()).isEqualTo("R06:REQ-20260515-000007");
            assertThat(x.traceId()).isEqualTo(7L);
            assertThat(x.evidenceDocIds()).containsExactly("doc-created");
            assertThat(x.details()).containsEntry("approvalStatus", "FAILED");
        });
        assertThat(scan(new R06StuckFlow(), new FakeRuleContext())).isEmpty();
    }

    @Test
    void rule007BruteForceNeedsFiveFailuresForOneUserWithinTenMinutes() {
        FakeRuleContext ctx = new FakeRuleContext();
        for (int i = 0; i < 6; i++) {
            ctx.lines.add(hit("f" + i, at("10:0" + i + ":10"), "usr-1042", null, i == 3 ? "MFA_FAILED" : "LOGIN_FAILED", "203.0.113.7", null, null, null));
        }
        // Another user's four failures, and four failures of the first user spread over an hour.
        for (int i = 0; i < 4; i++) {
            ctx.lines.add(hit("g" + i, at("10:0" + i + ":20"), "usr-2001", null, "LOGIN_FAILED", "10.1.0.4", null, null, null));
            ctx.lines.add(hit("h" + i, at("1" + (2 + i) + ":00:00"), "usr-1042", null, "LOGIN_FAILED", "10.1.0.3", null, null, null));
        }
        List<Finding> f = scan(new R07SignInBruteForce(), ctx);
        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.dedupKey()).isEqualTo("R07:usr-1042:2026-05-15T10:00:00Z");
            assertThat(x.userId()).isEqualTo("usr-1042");
            assertThat(x.clientIp()).isEqualTo("203.0.113.7");
            assertThat(x.evidenceDocIds()).containsExactly("f0", "f1", "f2", "f3", "f4", "f5");
        });

        FakeRuleContext quiet = new FakeRuleContext();
        quiet.lines.addAll(ctx.lines.stream().filter(h -> !h.id().startsWith("f")).toList());
        assertThat(scan(new R07SignInBruteForce(), quiet)).isEmpty();
    }

    @Test
    void rule008ConcurrentSessionsNeedOverlapFromDifferentIps() {
        FakeRuleContext ctx = new FakeRuleContext();
        ctx.lines.add(hit("in-a", at("09:00:00"), "usr-1042", "S-A", "LOGIN_OK", "10.1.0.3", null, null, null));
        ctx.lines.add(hit("in-b", at("09:20:00"), "usr-1042", "S-B", "LOGIN_OK", "203.0.113.9", null, null, null));
        ctx.lines.add(hit("out-b", at("09:25:00"), "usr-1042", "S-B", "LOGOUT", "203.0.113.9", null, null, null));
        ctx.lines.add(hit("out-a", at("09:40:00"), "usr-1042", "S-A", "LOGOUT", "10.1.0.3", null, null, null));
        // Sequential sessions from two IPs, and overlapping sessions from one IP, are fine.
        ctx.lines.add(hit("in-c", at("11:00:00"), "usr-2001", "S-C", "LOGIN_OK", "10.1.0.4", null, null, null));
        ctx.lines.add(hit("out-c", at("11:10:00"), "usr-2001", "S-C", "SESSION_EXPIRED", "10.1.0.4", null, null, null));
        ctx.lines.add(hit("in-d", at("11:30:00"), "usr-2001", "S-D", "LOGIN_OK", "10.1.0.8", null, null, null));
        ctx.lines.add(hit("out-d", at("11:40:00"), "usr-2001", "S-D", "LOGOUT", "10.1.0.8", null, null, null));
        ctx.lines.add(hit("in-e", at("12:00:00"), "usr-3001", "S-E", "LOGIN_OK", "10.1.0.5", null, null, null));
        ctx.lines.add(hit("in-f", at("12:05:00"), "usr-3001", "S-F", "LOGIN_OK", "10.1.0.5", null, null, null));
        List<Finding> f = scan(new R08ConcurrentSessions(), ctx);
        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.dedupKey()).isEqualTo("R08:usr-1042:S-A:S-B");
            assertThat(x.clientIp()).isEqualTo("203.0.113.9");
            assertThat(x.evidenceDocIds()).containsExactly("in-a", "in-b");
        });
    }

    @Test
    void rule014ProbingNeedsFiveDenialsOnThreeResourcesWithinTenMinutes() {
        FakeRuleContext ctx = new FakeRuleContext();
        for (int i = 0; i < 5; i++) {
            ctx.lines.add(hit("p" + i, at("14:0" + i + ":00"), "usr-1042", "S-P", "AUTHZ_DENIED", "10.1.0.3", null, null,
                    "REQ-20260514-00000" + (i % 4)));
        }
        // Five denials on a single resource are a confused user, not probing.
        for (int i = 0; i < 5; i++) {
            ctx.lines.add(hit("q" + i, at("15:0" + i + ":00"), "usr-2001", "S-Q", "AUTHZ_DENIED", "10.1.0.4", null, null,
                    "REQ-20260514-000009"));
        }
        List<Finding> f = scan(new R14AuthorizationProbing(), ctx);
        assertThat(f).singleElement().satisfies(x -> {
            assertThat(x.dedupKey()).isEqualTo("R14:usr-1042:2026-05-15T14:00:00Z");
            assertThat(x.evidenceDocIds()).hasSize(5);
            assertThat(x.details()).containsEntry("denials", 5);
        });
    }

    @Test
    void rule015ScannerTrafficByVolumeOrUserAgent() {
        FakeRuleContext ctx = new FakeRuleContext();
        Instant t = at("03:00:00");
        for (int i = 0; i < 120; i++) {
            ctx.lines.add(hit("s" + i, t.plusMillis(i * 2_000L), null, null, null, "203.0.113.50", i % 10 == 0 ? 200 : 404,
                    "Mozilla/5.0", null));
        }
        // A busy but healthy client: 150 requests, all 200.
        for (int i = 0; i < 150; i++) {
            ctx.lines.add(hit("b" + i, at("04:00:00").plusMillis(i * 1_000L), null, null, null, "10.1.0.3", 200, "Mozilla/5.0", null));
        }
        // Three requests with a scanner user agent.
        for (int i = 0; i < 3; i++) {
            ctx.lines.add(hit("u" + i, at("05:01:00").plusSeconds(i), null, null, null, "203.0.113.60", 404, "sqlmap/1.7", null));
        }
        List<Finding> f = scan(new R15ScannerTraffic(), ctx);
        assertThat(f).extracting(Finding::dedupKey)
                .containsExactly("R15:203.0.113.50:2026-05-15T03:00:00Z", "R15:203.0.113.60:2026-05-15T05:00:00Z");
        assertThat(f.get(0).evidenceDocIds()).hasSize(50).startsWith("s119");
        assertThat(f.get(0).details()).containsEntry("reason", "volume");
        assertThat(f.get(1).details()).containsEntry("reason", "userAgent");

        FakeRuleContext healthy = new FakeRuleContext();
        healthy.lines.addAll(ctx.lines.stream().filter(h -> h.id().startsWith("b")).toList());
        assertThat(scan(new R15ScannerTraffic(), healthy)).isEmpty();
    }

    @Test
    void scanRulesUseTheWindowAndNeverFireOnAnEmptyLog() {
        FakeRuleContext empty = new FakeRuleContext();
        for (AnomalyRule r : List.of(new R06StuckFlow(), new R07SignInBruteForce(), new R08ConcurrentSessions(),
                new R14AuthorizationProbing(), new R15ScannerTraffic())) {
            assertThat(r.scope()).isEqualTo(AnomalyRule.Scope.SCAN);
            assertThat(scan(r, empty)).as(r.id()).isEmpty();
        }
        LogHit outside = hit("x", FROM.minus(Duration.ofHours(1)), "usr-1042", null, "LOGIN_FAILED", "10.1.0.3", null, null, null);
        FakeRuleContext before = new FakeRuleContext();
        for (int i = 0; i < 10; i++) {
            before.lines.add(outside);
        }
        assertThat(scan(new R07SignInBruteForce(), before)).isEmpty();
    }
}
