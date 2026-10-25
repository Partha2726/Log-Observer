package com.ielop.platform.common.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ProcessingJobRepository extends JpaRepository<ProcessingJob, UUID> {

    List<ProcessingJob> findByStatusOrderByCreatedAtAsc(ProcessingJobStatus status);

    List<ProcessingJob> findByJobTypeAndStatus(String jobType, ProcessingJobStatus status);

    boolean existsByJobTypeAndStatusIn(String jobType, java.util.Collection<ProcessingJobStatus> statuses);

    @Query("SELECT j FROM ProcessingJob j WHERE j.status = 'QUEUED' ORDER BY j.createdAt ASC")
    List<ProcessingJob> findQueuedJobs();

    /**
     * Locks the oldest queued job of any type. {@code SKIP LOCKED} lets several
     * workers (threads or backend instances) claim different jobs without blocking.
     */
    @Query(value = "SELECT * FROM processing_jobs WHERE status = 'QUEUED' " +
                   "ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<ProcessingJob> lockNextQueued();

    /** Same as {@link #lockNextQueued()}, restricted to one job type. */
    @Query(value = "SELECT * FROM processing_jobs WHERE status = 'QUEUED' AND job_type = :jobType " +
                   "ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<ProcessingJob> lockNextQueuedOfType(@Param("jobType") String jobType);

    @Query("SELECT j FROM ProcessingJob j WHERE j.status = 'RUNNING' AND j.leaseExpiresAt < :now")
    List<ProcessingJob> findStaleRunningJobs(@Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE ProcessingJob j SET j.status = 'QUEUED', j.lockedBy = null, j.lockedAt = null, j.leaseExpiresAt = null " +
           "WHERE j.status = 'RUNNING' AND j.leaseExpiresAt < :now AND j.attemptCount < j.maxAttempts")
    int requeueStaleJobs(@Param("now") Instant now);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE ProcessingJob j SET j.status = 'FAILED', j.errorCode = 'LEASE_EXPIRED', j.errorMessage = 'Job exceeded lease timeout with max attempts reached', j.completedAt = :now, j.lockedBy = null " +
           "WHERE j.status = 'RUNNING' AND j.leaseExpiresAt < :now AND j.attemptCount >= j.maxAttempts")
    int failExhaustedStaleJobs(@Param("now") Instant now);
}
