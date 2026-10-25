package com.ielop.generator;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GEN-003, GEN-004 (every catalogue event), GEN-007 and GEN-008 on one 1,000-flow run. */
class NormalFlowTest {

    static final Pattern FLOW_REF = Pattern.compile("\\bREQ-\\d{8}-\\d{6}\\b");
    static final Set<String> USER_EVENTS = Set.of("LOGIN_OK", "LOGIN_FAILED", "MFA_FAILED", "LOGOUT", "AUTHZ_OK",
            "AUTHZ_DENIED", "PASSWORD_CHANGED", "ROLE_CHANGED");
    static final Set<String> FLOW_ACTIONS = Set.of("request.form_opened", "request.validation_failed", "request.created",
            "request.approval_started", "request.approved", "request.rejected", "execution.started",
            "execution.completed", "execution.failed");

    @TempDir
    static Path dir;
    static LogCorpus logs;

    @BeforeAll
    static void generate() throws Exception {
        GeneratorRun.run(dir, "--flows", "1000", "--days", "1", "--seed", "42", "--legacy-timezone", "Asia/Kolkata");
        logs = LogCorpus.read(dir.resolve("logs"));
    }

    // ---------------------------------------------------------------- GEN-003

    @Test
    void gatewayCarriesRequestIdAlwaysUserOnceSignedInAndFlowRefOnlyInActionUrls() {
        for (LogCorpus.Access a : logs.access) {
            assertTrue(a.rid().matches("[0-9a-f-]{36}"), a.line());
            if (a.ip().startsWith("203.0.113.")) {
                continue; // external clients: scanners and brute force, never signed in
            }
            boolean signedInCall = a.path().startsWith("/api/") || a.path().equals("/auth/logout");
            if (signedInCall) {
                assertFalse("-".equals(a.user()), () -> "signed-in call without user: " + a.line());
            }
            if (a.path().equals("/auth/login") || a.path().equals("/health")) {
                assertEquals("-", a.user(), a.line());
            }
            if (FLOW_REF.matcher(a.path()).find()) {
                assertTrue(a.path().matches("^/api/requests/[^/]+/(approve|reject|execute)$"), a.line());
            }
        }
    }

    @Test
    void workflowRequestLinesCarryRequestIdUserAndFlowRefFromTheFormOnwards() {
        int requestLines = 0;
        for (JsonNode n : logs.workflow) {
            if (!n.get("process.thread.name").asText().startsWith("http-nio-")) {
                assertFalse(n.has("http.request.id"), "background lines have no request ID");
                continue;
            }
            requestLines++;
            assertTrue(n.has("http.request.id"), n::toString);
            assertTrue(n.has("user.id") && n.has("session.id"), n::toString);
            if (n.has("event.action") && FLOW_ACTIONS.contains(n.get("event.action").asText())) {
                assertTrue(n.has("flow.ref"), n::toString);
            }
        }
        assertTrue(requestLines > 10_000);
    }

    @Test
    void authUserEventsCarryRequestIdAndUserAndSessionEventsCarrySession() {
        for (LogCorpus.Syslog s : logs.auth) {
            if (USER_EVENTS.contains(s.msgId())) {
                assertTrue(s.sd().containsKey("rid") && s.sd().containsKey("user"), s.line());
            }
            if (Set.of("LOGIN_OK", "LOGOUT", "SESSION_EXPIRED").contains(s.msgId())) {
                assertTrue(s.sd().containsKey("session"), s.line());
            }
        }
    }

    @Test
    void billingRequestIdAndFlowRefRatesMatchSection54() {
        List<LogCorpus.Billing> flowLines = logs.billing.stream().filter(b -> b.thread().startsWith("billing-worker-")).toList();
        long withRid = flowLines.stream().filter(b -> !b.requestId().isEmpty()).count();
        long withRef = flowLines.stream().filter(b -> FLOW_REF.matcher(b.text()).find()).count();
        double ridRate = withRid / (double) flowLines.size();
        double refRate = withRef / (double) flowLines.size();

        assertTrue(flowLines.size() > 10_000);
        assertTrue(Math.abs(ridRate - 0.60) <= 0.03, () -> "requestId rate " + ridRate);
        assertTrue(Math.abs(refRate - 0.40) <= 0.03, () -> "flowRef rate " + refRate);
    }

    // ---------------------------------------------------------------- GEN-004

    @Test
    void everyNormalCatalogueEventIsProduced() {
        Set<String> syslog = new HashSet<>();
        logs.auth.forEach(s -> syslog.add(s.msgId()));
        assertTrue(syslog.containsAll(Set.of("LOGIN_OK", "LOGIN_FAILED", "MFA_FAILED", "LOGOUT", "SESSION_EXPIRED", "AUTHZ_OK",
                "AUTHZ_DENIED", "PASSWORD_CHANGED", "ROLE_CHANGED")), syslog::toString);

        Set<String> actions = new HashSet<>();
        logs.workflow.forEach(n -> {
            if (n.has("event.action")) {
                actions.add(n.get("event.action").asText());
            }
        });
        assertTrue(actions.containsAll(Set.of("request.form_opened", "request.validation_failed", "request.created",
                "request.approval_started", "request.approved", "request.rejected", "execution.started",
                "execution.completed", "execution.failed")), actions::toString);

        for (String prefix : List.of("Reserved ", "Limit check passed ", "Provider call ", "Provider response ",
                "Published to billing.events ")) {
            assertTrue(logs.billing.stream().anyMatch(b -> b.text().startsWith(prefix)), prefix);
        }

        Set<String> calls = new HashSet<>();
        logs.access.forEach(a -> calls.add(a.method() + " " + a.path().replaceAll("REQ-\\d{8}-\\d{6}", "{ref}")));
        assertTrue(calls.containsAll(Set.of("POST /auth/login", "POST /auth/logout", "POST /api/requests",
                "POST /api/requests/{ref}/approve", "POST /api/requests/{ref}/execute")), calls::toString);
    }

    @Test
    void billingProducesStackTracesSqlAndXmlBodies() {
        assertTrue(logs.billing.stream().anyMatch(b -> b.text().contains("\nSELECT ") || b.text().contains("\nUPDATE ")));
        assertTrue(logs.billing.stream().anyMatch(b -> b.text().contains("\n<PayoutRequest")));
        assertTrue(logs.billing.stream().anyMatch(b -> b.text().contains("\n<PayoutResponse")));
    }

    // ---------------------------------------------------------------- GEN-007

    @Test
    void everyAccountNumberIsFakeWith999Prefix() {
        // Not inside a UUID or hex ID, whose groups can be all digits by chance.
        Pattern twelveDigits = Pattern.compile("(?<![\\w-])\\d{12}(?![\\w-])");
        List<String> texts = new java.util.ArrayList<>();
        logs.workflow.forEach(n -> texts.add(n.toString()));
        logs.billing.forEach(b -> texts.add(b.text()));
        logs.auth.forEach(s -> texts.add(s.line()));
        logs.access.forEach(a -> texts.add(a.line()));
        int accounts = 0;
        for (String text : texts) {
            Matcher m = twelveDigits.matcher(text);
            while (m.find()) {
                accounts++;
                assertTrue(m.group().startsWith("999"), () -> "non-fake account number in: " + text);
            }
        }
        assertTrue(accounts >= 1_000, "every created request carries a vendor account");
        for (JsonNode n : logs.workflow) {
            if (n.has("vendor_account")) {
                assertTrue(n.get("vendor_account").asText().matches("999\\d{9}"), n::toString);
            }
        }
    }

    // ---------------------------------------------------------------- GEN-008

    @Test
    void everySessionEndsInLogoutOrExpiry() {
        Set<String> opened = new HashSet<>();
        Set<String> closed = new HashSet<>();
        for (LogCorpus.Syslog s : logs.auth) {
            switch (s.msgId()) {
                case "LOGIN_OK" -> opened.add(s.sd().get("session"));
                case "LOGOUT", "SESSION_EXPIRED" -> closed.add(s.sd().get("session"));
                default -> { }
            }
        }
        assertTrue(opened.size() > 1_000);
        Set<String> open = new HashSet<>(opened);
        open.removeAll(closed);
        assertTrue(open.isEmpty(), () -> open.size() + " sessions never closed");
    }
}
