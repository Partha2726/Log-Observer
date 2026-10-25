package com.ielop.platform.common.job;

/**
 * Lifecycle states of an asynchronous background job.
 *
 * <p>Allowed transitions:
 * <ul>
 *   <li>QUEUED -> RUNNING</li>
 *   <li>RUNNING -> COMPLETED</li>
 *   <li>RUNNING -> FAILED</li>
 *   <li>FAILED -> QUEUED (bounded retry when attemptCount &lt; maxAttempts)</li>
 *   <li>QUEUED / RUNNING -> CANCELLED</li>
 * </ul>
 * </p>
 */
public enum ProcessingJobStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}
