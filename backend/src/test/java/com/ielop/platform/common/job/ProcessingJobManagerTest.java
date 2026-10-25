package com.ielop.platform.common.job;

import com.ielop.platform.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProcessingJobManagerTest extends IntegrationTest {

    @Autowired
    private ProcessingJobManager jobManager;

    @Autowired
    private ProcessingJobRepository jobRepository;

    @BeforeEach
    void clean() {
        jobRepository.deleteAll();
    }

    @Test
    void createAndClaimJobSuccessfully() {
        ProcessingJob job = jobManager.createJob("LOG_INGESTION", "payload://batch/1", 3);
        assertThat(job.getId()).isNotNull();
        assertThat(job.getStatus()).isEqualTo(ProcessingJobStatus.QUEUED);
        assertThat(job.getAttemptCount()).isEqualTo(0);

        Optional<ProcessingJob> claimed = jobManager.claimNextJob("LOG_INGESTION", "worker-1", Duration.ofMinutes(5));
        assertThat(claimed).isPresent();
        assertThat(claimed.get().getId()).isEqualTo(job.getId());
        assertThat(claimed.get().getStatus()).isEqualTo(ProcessingJobStatus.RUNNING);
        assertThat(claimed.get().getLockedBy()).isEqualTo("worker-1");
        assertThat(claimed.get().getAttemptCount()).isEqualTo(1);
        assertThat(claimed.get().getLeaseExpiresAt()).isAfter(Instant.now());
    }

    @Test
    void completeJobTransitionsToCompleted() {
        ProcessingJob job = jobManager.createJob("LOG_ARCHIVE", "payload://doc/1", 2);
        jobManager.claimNextJob("LOG_ARCHIVE", "worker-1", Duration.ofMinutes(5));

        jobManager.completeJob(job.getId(), "worker-1");

        ProcessingJob completed = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(completed.getStatus()).isEqualTo(ProcessingJobStatus.COMPLETED);
        assertThat(completed.getCompletedAt()).isNotNull();
        assertThat(completed.getLockedBy()).isNull();
    }

    @Test
    void failJobWithRetryRequeuesWhenAttemptsRemain() {
        ProcessingJob job = jobManager.createJob("LOG_INGESTION", "payload://batch/1", 3);
        jobManager.claimNextJob("LOG_INGESTION", "worker-1", Duration.ofMinutes(5));

        jobManager.failJob(job.getId(), "worker-1", "TIMEOUT", "Connection timed out", true);

        ProcessingJob requeued = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(requeued.getStatus()).isEqualTo(ProcessingJobStatus.QUEUED);
        assertThat(requeued.getAttemptCount()).isEqualTo(1);
        assertThat(requeued.getErrorCode()).isEqualTo("TIMEOUT");

        // Claim second attempt
        Optional<ProcessingJob> secondAttempt = jobManager.claimNextJob("LOG_INGESTION", "worker-2", Duration.ofMinutes(5));
        assertThat(secondAttempt).isPresent();
        assertThat(secondAttempt.get().getAttemptCount()).isEqualTo(2);
    }

    @Test
    void failJobPermanentlyWhenMaxAttemptsReached() {
        ProcessingJob job = jobManager.createJob("LOG_INGESTION", "payload://batch/1", 1);
        jobManager.claimNextJob("LOG_INGESTION", "worker-1", Duration.ofMinutes(5));

        jobManager.failJob(job.getId(), "worker-1", "FATAL_ERROR", "Unrecoverable", true);

        ProcessingJob failed = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(ProcessingJobStatus.FAILED);
        assertThat(failed.getCompletedAt()).isNotNull();
    }

    @Test
    void cannotCompleteUnclaimedOrNonRunningJob() {
        ProcessingJob job = jobManager.createJob("LOG_INGESTION", "payload://batch/1", 3);

        assertThatThrownBy(() -> jobManager.completeJob(job.getId(), "worker-1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recoverStaleJobsRequeuesOrFails() {
        ProcessingJob job = jobManager.createJob("LOG_INGESTION", "payload://batch/1", 3);
        jobManager.claimNextJob("LOG_INGESTION", "worker-1", Duration.ofMinutes(5));

        // Simulate stale lease in the past
        ProcessingJob runningJob = jobRepository.findById(job.getId()).orElseThrow();
        runningJob.setLeaseExpiresAt(Instant.now().minus(Duration.ofMinutes(10)));
        jobRepository.save(runningJob);

        int recovered = jobManager.recoverStaleJobs(Instant.now());
        assertThat(recovered).isGreaterThanOrEqualTo(1);

        ProcessingJob recoveredJob = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(recoveredJob.getStatus()).isEqualTo(ProcessingJobStatus.QUEUED);
    }
}
