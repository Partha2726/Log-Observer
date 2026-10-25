package com.ielop.platform.trace;

import com.ielop.platform.auth.security.AppUserPrincipal;
import com.ielop.platform.auth.security.JwtService;
import com.ielop.platform.common.job.ProcessingJobManager;
import com.ielop.platform.common.job.ProcessingJobRepository;
import com.ielop.platform.common.job.ProcessingJobStatus;
import com.ielop.platform.flowdef.FlowDefinitionRegistry;
import com.ielop.platform.logsearch.LogCriteria;
import com.ielop.platform.logsearch.LogFields;
import com.ielop.platform.logsearch.LogQueryService;
import com.ielop.platform.logsearch.TermsPage;
import com.ielop.platform.support.ElasticsearchIntegrationTest;
import com.ielop.platform.support.TestUsers;
import com.ielop.platform.user.RoleName;
import com.ielop.platform.user.RoleRepository;
import com.ielop.platform.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Bulk re-assembly (T30): resumable after a dead worker (JOB-003), bounded lease recovery (JOB-002), the admin endpoint. */
@AutoConfigureMockMvc
class BulkReassembleTest extends ElasticsearchIntegrationTest {

    static final Instant FROM = Instant.parse("2026-05-14T00:00:00Z");
    static final Instant TO = Instant.parse("2026-05-17T00:00:00Z");

    @Autowired
    private BulkReassembleService bulk;
    @Autowired
    private ProcessingJobManager jobs;
    @Autowired
    private ProcessingJobRepository repository;
    @Autowired
    private TraceService traces;
    @Autowired
    private LogQueryService logs;
    @Autowired
    private FlowDefinitionRegistry flows;
    @Autowired
    private JdbcTemplate jdbc;
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

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM processing_jobs WHERE job_type = 'BULK_REASSEMBLE'");
        bulk.pageSize(3);
    }

    private List<String> refsInRange() {
        List<String> all = new ArrayList<>();
        String after = null;
        do {
            TermsPage p = logs.termsPage("test", LogCriteria.builder().between(FROM, TO).hasFlowRef(true).build(), LogFields.FLOW_REFS, after, 100);
            p.values().stream().filter(r -> flows.forFlowRef(r).isPresent()).forEach(all::add);
            after = p.afterKey();
        } while (after != null);
        return all;
    }

    private void expireLease(UUID id) {
        jdbc.update("UPDATE processing_jobs SET lease_expires_at = now() - interval '1 minute' WHERE id = ?", id);
        jobs.recoverStaleJobs(Instant.now());
    }

    @Test
    void anInterruptedJobResumesFromTheRecordedPageWithoutRepeating() {
        List<String> refs = refsInRange();
        assertThat(refs.size()).isGreaterThan(3);
        UUID id = bulk.request(FROM, TO).orElseThrow().getId();
        assertThat(bulk.request(FROM, TO)).as("one bulk job at a time").isEmpty();
        long before = traces.assemblyCount();

        // The first worker does one page of 3 and dies.
        assertThat(bulk.runNext("worker-1", 1)).contains(id);
        var mid = repository.findById(id).orElseThrow();
        assertThat(mid.getStatus()).isEqualTo(ProcessingJobStatus.RUNNING);
        assertThat(mid.getPayloadReference()).contains("after=" + refs.get(2)).contains("done=3");
        assertThat(traces.assemblyCount() - before).isEqualTo(3);

        // Its lease expires; another worker takes over and finishes from the recorded point.
        expireLease(id);
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(ProcessingJobStatus.QUEUED);
        assertThat(bulk.runNext("worker-2")).contains(id);
        var done = repository.findById(id).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(ProcessingJobStatus.COMPLETED);
        assertThat(done.getPayloadReference()).contains("done=" + refs.size());
        assertThat(traces.assemblyCount() - before).as("every flowRef assembled exactly once").isEqualTo(refs.size());
    }

    @Test
    void aJobWhoseWorkersKeepDyingFailsAfterThreeAttempts() {
        UUID id = bulk.request(FROM, TO).orElseThrow().getId();
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(bulk.runNext("dying-" + attempt, 1)).contains(id);
            expireLease(id);
        }
        var job = repository.findById(id).orElseThrow();
        assertThat(job.getStatus()).isEqualTo(ProcessingJobStatus.FAILED);
        assertThat(job.getErrorCode()).isEqualTo("LEASE_EXPIRED");
        assertThat(job.getAttemptCount()).isEqualTo(3);
    }

    @Test
    void theAdminEndpointQueuesOneJobAndIsAudited() throws Exception {
        String admin = "bulk.admin." + UUID.randomUUID().toString().substring(0, 8);
        String adminToken = jwt.generateToken(new AppUserPrincipal(TestUsers.createUser(users, roles, encoder, admin, RoleName.ADMINISTRATOR, true)));
        String devops = jwt.generateToken(new AppUserPrincipal(TestUsers.createUser(users, roles, encoder, "bulk.devops." + UUID.randomUUID(),
                RoleName.BACKEND_DEVOPS_ENGINEER, true)));
        String url = "/api/admin/reassemble?from=2026-05-14T00:00:00Z&to=2026-05-17T00:00:00Z";
        mvc.perform(post(url).header("Authorization", "Bearer " + devops)).andExpect(status().isForbidden());
        mvc.perform(post(url).header("Authorization", "Bearer " + adminToken)).andExpect(status().isAccepted());
        mvc.perform(post(url).header("Authorization", "Bearer " + adminToken)).andExpect(status().isConflict());
        mvc.perform(post("/api/admin/reassemble?from=2026-05-17T00:00:00Z&to=2026-05-14T00:00:00Z").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE actor = ? AND action = 'ADMIN_REASSEMBLE'", Integer.class, admin))
                .isEqualTo(1);
    }
}
