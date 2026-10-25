package com.ielop.platform.common.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Service managing the lifecycle, claiming, retry, and stale-worker recovery of {@link ProcessingJob}s.
 *
 * <p>Centrally owned by {@code common.job}. Feature modules invoke this manager
 * to queue jobs and check job status.</p>
 */
@Service
public class ProcessingJobManager {

    private static final Logger log = LoggerFactory.getLogger(ProcessingJobManager.class);
    private static final Duration DEFAULT_LEASE_DURATION = Duration.ofMinutes(5);

    private final ProcessingJobRepository jobRepository;

    public ProcessingJobManager(ProcessingJobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Transactional
    public ProcessingJob createJob(String jobType, String payloadReference, int maxAttempts) {
        ProcessingJob job = new ProcessingJob(jobType, payloadReference, maxAttempts);
        return jobRepository.save(job);
    }

    @Transactional(readOnly = true)
    public Optional<ProcessingJob> getJob(UUID jobId) {
        return jobRepository.findById(jobId);
    }

    /**
     * Claims the oldest queued job (optionally of one type) for {@code workerId}.
     * The row is locked with {@code FOR UPDATE SKIP LOCKED}, so concurrent workers
     * never claim the same job (spec section 8.5).
     */
    @Transactional
    public Optional<ProcessingJob> claimNextJob(String jobType, String workerId, Duration leaseDuration) {
        Optional<ProcessingJob> candidate = jobType != null
                ? jobRepository.lockNextQueuedOfType(jobType)
                : jobRepository.lockNextQueued();
        if (candidate.isEmpty()) {
            return Optional.empty();
        }

        ProcessingJob job = candidate.get();
        Instant now = Instant.now();
        Duration lease = leaseDuration != null ? leaseDuration : DEFAULT_LEASE_DURATION;

        job.setStatus(ProcessingJobStatus.RUNNING);
        job.setStartedAt(now);
        job.setLockedBy(workerId);
        job.setLockedAt(now);
        job.setLeaseExpiresAt(now.plus(lease));
        job.incrementAttemptCount();

        log.info("Worker {} claimed job {} (type={}, attempt={}/{})",
                workerId, job.getId(), job.getJobType(), job.getAttemptCount(), job.getMaxAttempts());

        return Optional.of(jobRepository.save(job));
    }

    @Transactional
    public boolean heartbeat(UUID jobId, String workerId, Duration leaseExtension) {
        Optional<ProcessingJob> optJob = jobRepository.findById(jobId);
        if (optJob.isEmpty()) {
            return false;
        }

        ProcessingJob job = optJob.get();
        if (job.getStatus() != ProcessingJobStatus.RUNNING || !workerId.equals(job.getLockedBy())) {
            return false;
        }

        Duration extension = leaseExtension != null ? leaseExtension : DEFAULT_LEASE_DURATION;
        job.setLeaseExpiresAt(Instant.now().plus(extension));
        jobRepository.save(job);
        return true;
    }

    /**
     * Records a running job's progress in its payload and extends its lease, so a worker that dies
     * resumes from the last recorded point (section 7.7). Returns false if the job is no longer this worker's.
     */
    @Transactional
    public boolean recordProgress(UUID jobId, String workerId, String payload, Duration leaseExtension) {
        Optional<ProcessingJob> optJob = jobRepository.findById(jobId);
        if (optJob.isEmpty() || optJob.get().getStatus() != ProcessingJobStatus.RUNNING || !workerId.equals(optJob.get().getLockedBy())) {
            return false;
        }
        ProcessingJob job = optJob.get();
        job.setPayloadReference(payload);
        job.setLeaseExpiresAt(Instant.now().plus(leaseExtension != null ? leaseExtension : DEFAULT_LEASE_DURATION));
        jobRepository.save(job);
        return true;
    }

    @Transactional
    public void completeJob(UUID jobId, String workerId) {
        ProcessingJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        if (job.getStatus() != ProcessingJobStatus.RUNNING) {
            throw new IllegalStateException("Cannot complete job in status: " + job.getStatus());
        }

        Instant now = Instant.now();
        job.setStatus(ProcessingJobStatus.COMPLETED);
        job.setCompletedAt(now);
        job.setLockedBy(null);
        job.setLeaseExpiresAt(null);
        jobRepository.save(job);

        log.info("Job {} completed successfully by worker {}", jobId, workerId);
    }

    @Transactional
    public void failJob(UUID jobId, String workerId, String errorCode, String errorMessage, boolean retryable) {
        ProcessingJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        Instant now = Instant.now();
        job.setErrorCode(errorCode);
        job.setErrorMessage(errorMessage);
        job.setLockedBy(null);
        job.setLeaseExpiresAt(null);

        if (retryable && job.getAttemptCount() < job.getMaxAttempts()) {
            job.setStatus(ProcessingJobStatus.QUEUED);
            log.warn("Job {} failed with code '{}' (attempt {}/{}). Requeued for retry.",
                    jobId, errorCode, job.getAttemptCount(), job.getMaxAttempts());
        } else {
            job.setStatus(ProcessingJobStatus.FAILED);
            job.setCompletedAt(now);
            log.error("Job {} permanently failed with code '{}': {}", jobId, errorCode, errorMessage);
        }

        jobRepository.save(job);
    }

    @Transactional
    public void cancelJob(UUID jobId) {
        ProcessingJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        if (job.getStatus() == ProcessingJobStatus.COMPLETED || job.getStatus() == ProcessingJobStatus.FAILED) {
            throw new IllegalStateException("Cannot cancel finished job in status: " + job.getStatus());
        }

        job.setStatus(ProcessingJobStatus.CANCELLED);
        job.setCompletedAt(Instant.now());
        job.setLockedBy(null);
        job.setLeaseExpiresAt(null);
        jobRepository.save(job);

        log.info("Job {} cancelled", jobId);
    }

    @Transactional
    public int recoverStaleJobs(Instant now) {
        Instant effectiveNow = now != null ? now : Instant.now();
        int requeued = jobRepository.requeueStaleJobs(effectiveNow);
        int failed = jobRepository.failExhaustedStaleJobs(effectiveNow);
        int total = requeued + failed;

        if (total > 0) {
            log.warn("Stale job recovery: requeued {} jobs, permanently failed {} exhausted jobs", requeued, failed);
        }
        return total;
    }
}
