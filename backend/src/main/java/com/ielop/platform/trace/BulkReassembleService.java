package com.ielop.platform.trace;

import com.ielop.platform.common.job.ProcessingJob;
import com.ielop.platform.common.job.ProcessingJobManager;
import com.ielop.platform.common.job.ProcessingJobRepository;
import com.ielop.platform.common.job.ProcessingJobStatus;
import com.ielop.platform.flowdef.FlowDefinitionRegistry;
import com.ielop.platform.logsearch.LogCriteria;
import com.ielop.platform.logsearch.LogFields;
import com.ielop.platform.logsearch.LogQueryService;
import com.ielop.platform.logsearch.TermsPage;
import com.ielop.platform.trace.assembly.TraceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code BULK_REASSEMBLE} jobs (section 7.7): every flowRef seen in {@code [from, to]}, in pages of
 * 500 (composite aggregation, ordered by flowRef), re-assembled one transaction per trace (the trace
 * rules run after each). After each page the job's payload records the last flowRef and the counts,
 * so a job taken over after a dead worker's lease expired resumes from there instead of repeating.
 * Payload: {@code from=…;to=…;after=…;done=…;failed=…}.
 */
@Service
public class BulkReassembleService {

    public static final String JOB_TYPE = "BULK_REASSEMBLE";
    static final Duration LEASE = Duration.ofMinutes(10);
    private static final Logger log = LoggerFactory.getLogger(BulkReassembleService.class);
    private static final List<ProcessingJobStatus> ACTIVE = List.of(ProcessingJobStatus.QUEUED, ProcessingJobStatus.RUNNING);

    private final ProcessingJobManager jobs;
    private final ProcessingJobRepository repository;
    private final LogQueryService logs;
    private final TraceService traces;
    private final FlowDefinitionRegistry flows;
    private int pageSize = 500;

    public BulkReassembleService(ProcessingJobManager jobs, ProcessingJobRepository repository, LogQueryService logs, TraceService traces,
                                 FlowDefinitionRegistry flows) {
        this.jobs = jobs;
        this.repository = repository;
        this.logs = logs;
        this.traces = traces;
        this.flows = flows;
    }

    /** Smaller pages for tests. */
    void pageSize(int size) {
        this.pageSize = size;
    }

    public record Progress(Instant from, Instant to, String after, long done, long failed) {
        static Progress parse(String payload) {
            Map<String, String> m = new LinkedHashMap<>();
            for (String part : payload.split(";")) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    m.put(part.substring(0, eq), part.substring(eq + 1));
                }
            }
            String after = m.get("after");
            return new Progress(Instant.parse(m.get("from")), Instant.parse(m.get("to")), after == null || after.isEmpty() ? null : after,
                    Long.parseLong(m.getOrDefault("done", "0")), Long.parseLong(m.getOrDefault("failed", "0")));
        }

        String payload() {
            return "from=" + from + ";to=" + to + ";after=" + (after == null ? "" : after) + ";done=" + done + ";failed=" + failed;
        }
    }

    @Transactional
    public Optional<ProcessingJob> request(Instant from, Instant to) {
        if (repository.existsByJobTypeAndStatusIn(JOB_TYPE, ACTIVE)) {
            return Optional.empty();
        }
        return Optional.of(jobs.createJob(JOB_TYPE, new Progress(from, to, null, 0, 0).payload(), 3));
    }

    /** Claims and runs one queued job to its end; returns its ID. */
    public Optional<UUID> runNext(String workerId) {
        return runNext(workerId, Integer.MAX_VALUE);
    }

    /** As {@link #runNext(String)}, stopping (as if the worker died) after {@code maxPages} pages. */
    Optional<UUID> runNext(String workerId, int maxPages) {
        Optional<ProcessingJob> claimed = jobs.claimNextJob(JOB_TYPE, workerId, LEASE);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        ProcessingJob job = claimed.get();
        Progress p = Progress.parse(job.getPayloadReference());
        try {
            LogCriteria criteria = LogCriteria.builder().between(p.from(), p.to()).hasFlowRef(true).build();
            for (int page = 0; page < maxPages; page++) {
                TermsPage refs = logs.termsPage("bulk.reassemble", criteria, LogFields.FLOW_REFS, p.after(), pageSize);
                long done = p.done();
                long failed = p.failed();
                for (String ref : refs.values()) {
                    if (flows.forFlowRef(ref).isEmpty()) {
                        continue;
                    }
                    try {
                        traces.getOrAssemble(ref, true);
                        done++;
                    } catch (TraceNotFoundException e) {
                        failed++;
                    }
                }
                String last = refs.values().isEmpty() ? p.after() : refs.values().get(refs.values().size() - 1);
                p = new Progress(p.from(), p.to(), last, done, failed);
                if (!jobs.recordProgress(job.getId(), workerId, p.payload(), LEASE)) {
                    log.warn("Bulk re-assembly {} was taken over; stopping", job.getId());
                    return Optional.of(job.getId());
                }
                if (refs.afterKey() == null) {
                    jobs.completeJob(job.getId(), workerId);
                    log.info("Bulk re-assembly {} done: {} traces, {} without logs", job.getId(), p.done(), p.failed());
                    return Optional.of(job.getId());
                }
            }
            return Optional.of(job.getId());
        } catch (RuntimeException e) {
            log.error("Bulk re-assembly {} failed", job.getId(), e);
            jobs.failJob(job.getId(), workerId, "BULK_REASSEMBLE_FAILED", String.valueOf(e.getMessage()), true);
            return Optional.of(job.getId());
        }
    }
}
