package com.ielop.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GEN-005 for the scenarios whose signature spans flows, users or client IPs
 * (section 5.5): each signature is checked on the generated log lines themselves.
 */
class IncidentScenarioTest {

    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    @TempDir
    static Path dir;
    static LogCorpus logs;
    static List<JsonNode> flows;
    static List<JsonNode> incidents;
    static Map<String, JsonNode> users = new HashMap<>();

    @BeforeAll
    static void generate() throws Exception {
        GeneratorRun.run(dir, "--flows", "1000", "--days", "1", "--seed", "42", "--legacy-timezone", "Asia/Kolkata");
        logs = LogCorpus.read(dir.resolve("logs"));
        flows = jsonl("flows.jsonl");
        incidents = jsonl("incidents.jsonl");
        jsonl("users.jsonl").forEach(u -> users.put(u.get("userId").asText(), u));
    }

    static List<JsonNode> jsonl(String name) throws Exception {
        ObjectMapper json = new ObjectMapper();
        List<JsonNode> out = new ArrayList<>();
        for (String line : Files.readAllLines(dir.resolve("ground-truth").resolve(name), StandardCharsets.UTF_8)) {
            out.add(json.readTree(line));
        }
        return out;
    }

    static List<JsonNode> incidents(String scenario) {
        List<JsonNode> out = incidents.stream().filter(i -> i.get("scenario").asText().equals(scenario)).toList();
        assertFalse(out.isEmpty(), scenario);
        return out;
    }

    static Instant ts(String line) {
        return Instant.parse(line.split(" ")[1]);
    }

    @Test
    void bruteForceIsEightToFifteenFailuresFromOneExternalIpWithinFiveMinutes() {
        for (JsonNode i : incidents("BRUTE_FORCE_LOGIN")) {
            String user = i.get("userId").asText();
            String ip = i.get("clientIp").asText();
            List<Instant> failures = logs.auth.stream()
                    .filter(s -> Set.of("LOGIN_FAILED", "MFA_FAILED").contains(s.msgId()) && user.equals(s.sd().get("user")))
                    .map(s -> ts(s.line())).sorted().toList();
            assertTrue(failures.size() >= 8 && failures.size() <= 15, user + ": " + failures.size());
            assertTrue(Duration.between(failures.get(0), failures.get(failures.size() - 1)).toMinutes() < 5, user);
            assertTrue(logs.auth.stream().anyMatch(s -> s.msgId().equals("LOGIN_OK") && ip.equals(s.sd().get("ip"))
                    && user.equals(s.sd().get("user"))), "then a success from the same IP");
        }
    }

    @Test
    void concurrentSessionsComeFromDifferentIpsAndOverlap() {
        for (JsonNode i : incidents("CONCURRENT_SESSIONS")) {
            String user = i.get("userId").asText();
            Map<String, Instant[]> sessions = new HashMap<>();
            Map<String, String> ipOf = new HashMap<>();
            for (LogCorpus.Syslog s : logs.auth) {
                if (!user.equals(s.sd().get("user")) || !s.sd().containsKey("session")) {
                    continue;
                }
                String id = s.sd().get("session");
                Instant t = ts(s.line());
                if (s.msgId().equals("LOGIN_OK")) {
                    sessions.put(id, new Instant[]{t, Instant.MAX});
                    ipOf.put(id, s.sd().get("ip"));
                } else if (sessions.containsKey(id)) {
                    sessions.get(id)[1] = t;
                }
            }
            boolean overlap = false;
            List<String> ids = new ArrayList<>(sessions.keySet());
            for (int a = 0; a < ids.size(); a++) {
                for (int b = a + 1; b < ids.size(); b++) {
                    Instant[] x = sessions.get(ids.get(a));
                    Instant[] y = sessions.get(ids.get(b));
                    overlap |= x[0].isBefore(y[1]) && y[0].isBefore(x[1]) && !ipOf.get(ids.get(a)).equals(ipOf.get(ids.get(b)));
                }
            }
            assertTrue(overlap, user);
        }
    }

    @Test
    void authzProbingIsFiveOrMoreDenialsOnThreeOrMoreRequestsWithinTenMinutes() {
        for (JsonNode i : incidents("AUTHZ_PROBING")) {
            String user = i.get("userId").asText();
            List<LogCorpus.Syslog> denials = logs.auth.stream()
                    .filter(s -> s.msgId().equals("AUTHZ_DENIED") && user.equals(s.sd().get("user"))).toList();
            Set<String> resources = new HashSet<>();
            denials.forEach(d -> resources.add(d.sd().get("resource")));
            assertTrue(denials.size() >= 5 && denials.size() <= 20, user);
            assertTrue(resources.size() >= 3, user);
            assertTrue(Duration.between(ts(denials.get(0).line()), ts(denials.get(denials.size() - 1).line())).toMinutes() < 10);
            assertTrue(users.get(user).get("limitAmount").isNull(), "the prober holds no approve permission");
            long forbidden = logs.access.stream().filter(a -> a.status() == 403 && user.equals(a.user())).count();
            assertEquals(denials.size(), forbidden);
        }
    }

    @Test
    void scannerTrafficIsHundredsOfMostlyRejectedRequestsFromOneIpWithAScannerAgent() {
        for (JsonNode i : incidents("SCANNER_TRAFFIC")) {
            String ip = i.get("clientIp").asText();
            List<LogCorpus.Access> lines = logs.access.stream().filter(a -> a.ip().equals(ip) && "-".equals(a.user())).toList();
            long rejected = lines.stream().filter(a -> Set.of(401, 403, 404).contains(a.status())).count();
            assertTrue(lines.size() >= 100 && lines.size() <= 400, ip + ": " + lines.size());
            assertTrue(rejected >= 0.6 * lines.size(), ip);
            assertTrue(Set.of("sqlmap/1.7", "python-requests/2.31.0", "Nikto/2.5.0", "masscan/1.3").contains(lines.get(0).userAgent()));
        }
    }

    @Test
    void flowScenarioSignatures() {
        Map<String, Instant> created = new HashMap<>();
        for (var n : logs.workflow) {
            if (n.has("event.action") && n.get("event.action").asText().equals("request.created")) {
                created.put(n.get("flow.ref").asText(), Instant.parse(n.get("@timestamp").asText()));
            }
        }
        Map<String, Instant> approved = new HashMap<>();
        for (var n : logs.workflow) {
            if (n.has("event.action") && n.get("event.action").asText().equals("request.approved")) {
                approved.put(n.get("flow.ref").asText(), Instant.parse(n.get("@timestamp").asText()));
            }
        }
        List<JsonNode> duplicates = new ArrayList<>();
        for (JsonNode f : flows) {
            String ref = f.get("flowRef").asText();
            switch (f.get("scenario").asText()) {
                case "LIMIT_BREACH" -> {
                    BigDecimal limit = users.get(f.get("approver").asText()).get("limitAmount").decimalValue();
                    assertTrue(f.get("amount").decimalValue().compareTo(limit) > 0, ref);
                }
                case "OFF_HOURS" -> {
                    int hour = approved.get(ref).atZone(ZONE).getHour();
                    assertTrue(hour < 5, ref + " approved at " + hour);
                }
                case "SELF_APPROVAL" -> assertEquals(f.get("requester").asText(), f.get("approver").asText());
                case "DUPLICATE_SUBMIT" -> duplicates.add(f);
                default -> { }
            }
        }
        assertEquals(0, duplicates.size() % 2);
        for (int k = 0; k < duplicates.size(); k += 2) {
            JsonNode a = duplicates.get(k);
            JsonNode b = duplicates.get(k + 1);
            assertEquals(a.get("requester").asText(), b.get("requester").asText());
            assertEquals(0, a.get("amount").decimalValue().compareTo(b.get("amount").decimalValue()));
            Duration gap = Duration.between(created.get(a.get("flowRef").asText()), created.get(b.get("flowRef").asText())).abs();
            assertTrue(gap.toSeconds() <= 60, gap.toString());
        }
    }
}
