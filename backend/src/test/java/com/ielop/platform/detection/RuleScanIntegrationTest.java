package com.ielop.platform.detection;

import com.fasterxml.jackson.databind.JsonNode;
import com.ielop.platform.auth.security.AppUserPrincipal;
import com.ielop.platform.auth.security.JwtService;
import com.ielop.platform.common.job.ProcessingJobRepository;
import com.ielop.platform.common.job.ProcessingJobStatus;
import com.ielop.platform.support.ElasticsearchIntegrationTest;
import com.ielop.platform.support.EsFixtures;
import com.ielop.platform.support.TestUsers;
import com.ielop.platform.trace.TraceService;
import com.ielop.platform.user.RoleName;
import com.ielop.platform.user.RoleRepository;
import com.ielop.platform.user.UserRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Array;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Rules on generated data (T21): every scenario of a 1,000-flow run fires its expected rule
 * (RULE-020), scans are idempotent and leave reviewed rows alone (RULE-021), scan evidence
 * belongs to the user or IP it names (RULE-022), and one scan job is claimed by one worker
 * (JOB-001). The generator ran with the legacy timezone Asia/Kolkata, so that is the business
 * timezone here; the fixture holds 46 flows, so R09's minimum baseline is lowered to 3.
 */
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {"app.rules.business-timezone=Asia/Kolkata", "app.rules.slow-stage-min-samples=3"})
class RuleScanIntegrationTest extends ElasticsearchIntegrationTest {

    static final Instant FROM = Instant.parse("2026-06-09T00:00:00Z");
    static final Instant TO = Instant.parse("2026-06-11T12:00:00Z");

    /** Section 5.5: the rules each flow scenario must fire. */
    static final Map<String, Set<String>> EXPECTED = Map.ofEntries(
            Map.entry("FAIL_DB_TIMEOUT_APPROVE", Set.of("R05", "R06")),
            Map.entry("FAIL_DB_DOWN_CREATE", Set.of("R05")),
            Map.entry("FAIL_VALIDATION_CREATE", Set.of("R05")),
            Map.entry("FAIL_PROVIDER_TIMEOUT_EXECUTE", Set.of("R05", "R13")),
            Map.entry("SELF_APPROVAL", Set.of("R01")),
            Map.entry("EXECUTE_WITHOUT_APPROVAL", Set.of("R03")),
            Map.entry("OUT_OF_ORDER", Set.of("R04")),
            Map.entry("SLOW_APPROVE", Set.of("R09")),
            Map.entry("LIMIT_BREACH", Set.of("R10")),
            Map.entry("OFF_HOURS", Set.of("R12")));
    /** Rules a NORMAL flow may fire at their natural base rate (section 10.3). */
    static final Set<String> BASE_RATE = Set.of("R09", "R12");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JwtService jwt;
    @Autowired
    private UserRepository users;
    @Autowired
    private RoleRepository roles;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private TraceService traces;
    @Autowired
    private RuleScanService scans;
    @Autowired
    private ProcessingJobRepository jobs;
    @Autowired
    private JdbcTemplate jdbc;

    private String admin;

    @BeforeAll
    void indexAssembleAndScan() throws Exception {
        List<EsFixtures.Doc> docs = EsFixtures.rulesDocs();
        for (int i = 0; i < docs.size(); i += 500) {
            index(docs.subList(i, Math.min(docs.size(), i + 500)));
        }
        admin = token(RoleName.ADMINISTRATOR);
        byte[] limits = Files.readAllBytes(Path.of("src/test/resources/fixtures/es/rules-users.jsonl"));
        mvc.perform(multipart("/api/admin/user-limits/import").file(new MockMultipartFile("file", "users.jsonl", "application/x-ndjson", limits))
                .header("Authorization", "Bearer " + admin)).andExpect(status().isOk());
        for (JsonNode f : flows()) {
            traces.getOrAssemble(f.get("flowRef").asText(), true);
        }
        clearActiveScans();
        scanOnce();
    }

    private String token(RoleName role) {
        return jwt.generateToken(new AppUserPrincipal(TestUsers.createUser(users, roles, encoder,
                "scan." + role.name().toLowerCase() + "." + UUID.randomUUID(), role, true)));
    }

    private static List<JsonNode> flows() {
        return EsFixtures.read("/fixtures/es/rules-flows.jsonl");
    }

    private static List<JsonNode> incidents() {
        return EsFixtures.read("/fixtures/es/rules-incidents.jsonl");
    }

    private void clearActiveScans() {
        jdbc.update("DELETE FROM processing_jobs WHERE job_type = 'RULE_SCAN' AND status IN ('QUEUED', 'RUNNING')");
    }

    private void scanOnce() {
        UUID id = scans.request(FROM, TO).orElseThrow().getId();
        assertThat(scans.runNext("test-worker")).contains(id);
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo(ProcessingJobStatus.COMPLETED);
    }

    private Map<String, Set<String>> rulesByFlowRef() {
        Map<String, Set<String>> out = new HashMap<>();
        jdbc.query("SELECT t.flow_ref, a.rule_id FROM anomaly a JOIN trace t ON t.id = a.trace_id WHERE t.flow_ref LIKE 'REQ-20260610-%'",
                rs -> {
                    out.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2));
                });
        return out;
    }

    // ------------------------------------------------------------------ RULE-020

    @Test
    void everyScenarioFiresItsExpectedRuleAndNormalFlowsOnlyHitBaseRateRules() {
        Map<String, Set<String>> fired = rulesByFlowRef();
        List<String> missing = new ArrayList<>();
        Map<String, Integer> duplicatesFound = new HashMap<>();
        for (JsonNode f : flows()) {
            String scenario = f.get("scenario").asText();
            Set<String> got = fired.getOrDefault(f.get("flowRef").asText(), Set.of());
            if (EXPECTED.containsKey(scenario) && !got.containsAll(EXPECTED.get(scenario))) {
                missing.add(scenario + " " + f.get("flowRef").asText() + " fired " + got);
            }
            if (scenario.equals("DUPLICATE_SUBMIT") && got.contains("R11")) {
                duplicatesFound.merge(scenario, 1, Integer::sum);
            }
            if (scenario.equals("NORMAL") || scenario.equals("APPROVER_REJECT") || scenario.equals("SESSION_TIMEOUT")) {
                assertThat(got).as(scenario + " " + f.get("flowRef").asText() + " " + jdbc.queryForList(
                        "SELECT a.rule_id, l.es_doc_id, l.request_id, l.event_ts, l.thread, l.evidence_class, l.link_reason, l.error_type, l.provider_status, s.status "
                                + "FROM anomaly a JOIN trace t ON t.id = a.trace_id JOIN trace_event_link l ON l.trace_id = t.id AND l.es_doc_id = ANY(a.evidence_doc_ids) "
                                + "JOIN trace_stage s ON s.trace_id = t.id AND s.stage = l.stage WHERE t.flow_ref = ?", f.get("flowRef").asText()))
                        .isSubsetOf(BASE_RATE);
            }
        }
        assertThat(missing).isEmpty();
        assertThat(duplicatesFound.get("DUPLICATE_SUBMIT")).as("one R11 row per pair, on the later flow").isEqualTo(1);

        for (JsonNode i : incidents()) {
            String rule = i.get("expectedRule").asText();
            String subject = rule.equals("R15") ? i.get("clientIp").asText() : i.get("userId").asText();
            String column = rule.equals("R15") ? "host(client_ip)" : "user_id";
            Integer n = jdbc.queryForObject("SELECT count(*) FROM anomaly WHERE rule_id = ? AND " + column + " = ? "
                            + "AND window_start <= ?::timestamptz + interval '1 minute' AND window_end >= ?::timestamptz - interval '1 minute'",
                    Integer.class, rule, subject, i.get("windowEnd").asText(), i.get("windowStart").asText());
            assertThat(n).as(i.get("scenario").asText() + " " + subject).isPositive();
        }
    }

    // ------------------------------------------------------------------ RULE-021, RULE-022

    @Test
    void scanningAgainAddsNoRowsAndLeavesReviewedRowsAlone() {
        long reviewed = jdbc.queryForObject("SELECT min(id) FROM anomaly WHERE rule_id IN ('R07', 'R14', 'R15')", Long.class);
        jdbc.update("UPDATE anomaly SET status = 'CONFIRMED', reviewed_by = 'reviewer', review_note = 'checked', "
                + "evidence_doc_ids = ARRAY['kept'], version = version + 1 WHERE id = ?", reviewed);
        int before = jdbc.queryForObject("SELECT count(*) FROM anomaly", Integer.class);

        scanOnce();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM anomaly", Integer.class)).isEqualTo(before);
        Map<String, Object> row = jdbc.queryForMap("SELECT status, evidence_doc_ids, version FROM anomaly WHERE id = ?", reviewed);
        assertThat(row.get("status")).isEqualTo("CONFIRMED");
        assertThat(row.get("version")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT evidence_doc_ids[1] FROM anomaly WHERE id = ?", String.class, reviewed)).isEqualTo("kept");
    }

    @Test
    void scanEvidenceBelongsToTheUserOrIpOfTheFinding() throws Exception {
        Map<String, EsFixtures.Doc> byId = new HashMap<>();
        EsFixtures.rulesDocs().forEach(d -> byId.put(d.id(), d));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, rule_id, user_id, host(client_ip) AS ip, evidence_doc_ids FROM anomaly WHERE trace_id IS NULL AND status = 'NEW' "
                        + "AND window_start BETWEEN ?::timestamptz AND ?::timestamptz", FROM.toString(), TO.toString());
        assertThat(rows).extracting(r -> r.get("rule_id")).contains("R07", "R08", "R14", "R15");
        for (Map<String, Object> r : rows) {
            List<String> evidence = Arrays.asList((String[]) ((Array) r.get("evidence_doc_ids")).getArray());
            assertThat(evidence).isNotEmpty().hasSizeLessThanOrEqualTo(50);
            for (String id : evidence) {
                EsFixtures.Doc d = byId.get(id);
                assertThat(d).as("evidence %s of anomaly %s is a fixture document", id, r.get("id")).isNotNull();
                if ("R15".equals(r.get("rule_id"))) {
                    assertThat(d.text("client.ip")).isEqualTo(r.get("ip"));
                } else {
                    assertThat(d.text("user.id")).as("anomaly %s (%s)", r.get("id"), r.get("rule_id")).isEqualTo(r.get("user_id"));
                }
            }
        }
    }

    // ------------------------------------------------------------------ JOB-001 and the admin endpoint

    @Test
    void oneQueuedScanIsClaimedByExactlyOneWorker() throws Exception {
        clearActiveScans();
        UUID id = scans.request(FROM, TO).orElseThrow().getId();
        assertThat(scans.request(FROM, TO)).as("a second scan is refused while one is queued").isEmpty();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Optional<UUID>>> workers = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                String worker = "worker-" + i;
                workers.add(() -> scans.runNext(worker));
            }
            List<UUID> ran = new ArrayList<>();
            for (Future<Optional<UUID>> f : pool.invokeAll(workers)) {
                f.get().ifPresent(ran::add);
            }
            assertThat(ran).containsExactly(id);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jobs.findById(id).orElseThrow().getAttemptCount()).isEqualTo(1);
    }

    @Test
    void adminRuleScanEndpointQueuesOneScanAtATime() throws Exception {
        clearActiveScans();
        String devops = token(RoleName.BACKEND_DEVOPS_ENGINEER);
        mvc.perform(post("/api/admin/rule-scan").header("Authorization", "Bearer " + devops)).andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/rule-scan?from=2026-06-11T00:00:00Z&to=2026-06-10T00:00:00Z").header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.from").exists());
        mvc.perform(post("/api/admin/rule-scan?from=2026-01-01T00:00:00Z&to=2026-06-10T00:00:00Z").header("Authorization", "Bearer " + admin))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/rule-scan?from=2026-06-09T00:00:00Z&to=2026-06-11T12:00:00Z").header("Authorization", "Bearer " + admin))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.jobId").exists());
        mvc.perform(post("/api/admin/rule-scan").header("Authorization", "Bearer " + admin))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONFLICT"));
        assertThat(scans.runNext("endpoint-worker")).isPresent();
        String body = new String(mvc.perform(post("/api/admin/rule-scan").header("Authorization", "Bearer " + admin))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertThat(body).contains("jobId");
        clearActiveScans();
    }
}
