package com.ielop.platform.common.job;

import com.ielop.platform.common.exception.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/** {@code GET /api/jobs/{id}} (section 8.2): a processing job's status, for polling. */
@RestController
@Tag(name = "Jobs")
public class JobController {

    public record JobStatus(UUID id, String type, String status, int attemptCount, int maxAttempts, String errorCode,
                            Instant createdAt, Instant startedAt, Instant completedAt) {
    }

    private final ProcessingJobManager jobs;

    public JobController(ProcessingJobManager jobs) {
        this.jobs = jobs;
    }

    @Operation(summary = "Status of a processing job (AI_SUMMARY, RULE_SCAN, BULK_REASSEMBLE)")
    @GetMapping("/api/jobs/{id}")
    public JobStatus get(@PathVariable UUID id) {
        ProcessingJob j = jobs.getJob(id).orElseThrow(() -> ApiException.notFound("No job " + id));
        return new JobStatus(j.getId(), j.getJobType(), j.getStatus().name(), j.getAttemptCount(), j.getMaxAttempts(), j.getErrorCode(),
                j.getCreatedAt(), j.getStartedAt(), j.getCompletedAt());
    }
}
