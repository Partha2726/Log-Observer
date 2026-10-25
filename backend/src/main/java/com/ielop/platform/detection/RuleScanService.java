package com.ielop.platform.detection;

import com.ielop.platform.common.job.ProcessingJob;
import com.ielop.platform.common.job.ProcessingJobManager;
import com.ielop.platform.common.job.ProcessingJobRepository;
import com.ielop.platform.common.job.ProcessingJobStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code RULE_SCAN} jobs (sections 8.5 and 10): the scan rules over a window, run by a
 * worker that claims the job with {@code FOR UPDATE SKIP LOCKED}. The payload is
 * {@code from=<ISO instant>;to=<ISO instant>}.
 */
@Service
public class RuleScanService {

    public static final String JOB_TYPE = "RULE_SCAN";
    private static final Logger log = LoggerFactory.getLogger(RuleScanService.class);
    private static final Duration LEASE = Duration.ofMinutes(30);
    private static final List<ProcessingJobStatus> ACTIVE = List.of(ProcessingJobStatus.QUEUED, ProcessingJobStatus.RUNNING);

    private final ProcessingJobManager jobs;
    private final ProcessingJobRepository repository;
    private final RuleEngine engine;

    public RuleScanService(ProcessingJobManager jobs, ProcessingJobRepository repository, RuleEngine engine) {
        this.jobs = jobs;
        this.repository = repository;
        this.engine = engine;
    }

    public record Window(Instant from, Instant to) {
        String payload() {
            return "from=" + from + ";to=" + to;
        }

        static Window parse(String payload) {
            String[] parts = payload.split(";");
            return new Window(Instant.parse(parts[0].substring("from=".length())), Instant.parse(parts[1].substring("to=".length())));
        }
    }

    /** Queues a scan unless one is already queued or running. */
    @Transactional
    public Optional<ProcessingJob> request(Instant from, Instant to) {
        if (repository.existsByJobTypeAndStatusIn(JOB_TYPE, ACTIVE)) {
            return Optional.empty();
        }
        return Optional.of(jobs.createJob(JOB_TYPE, new Window(from, to).payload(), 3));
    }

    /** Claims and runs one queued scan; returns the job ID it ran. */
    public Optional<UUID> runNext(String workerId) {
        Optional<ProcessingJob> claimed = jobs.claimNextJob(JOB_TYPE, workerId, LEASE);
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        ProcessingJob job = claimed.get();
        try {
            Window w = Window.parse(job.getPayloadReference());
            int findings = engine.scan(w.from(), w.to()).size();
            jobs.completeJob(job.getId(), workerId);
            log.info("Rule scan {} over {} .. {}: {} findings", job.getId(), w.from(), w.to(), findings);
        } catch (RuntimeException e) {
            log.error("Rule scan {} failed", job.getId(), e);
            jobs.failJob(job.getId(), workerId, "RULE_SCAN_FAILED", String.valueOf(e.getMessage()), true);
        }
        return Optional.of(job.getId());
    }
}
