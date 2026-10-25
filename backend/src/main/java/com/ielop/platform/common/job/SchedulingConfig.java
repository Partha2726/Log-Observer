package com.ielop.platform.common.job;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Instant;

/**
 * Background scheduling for every job worker, and lease recovery (section 8.5): a RUNNING job whose
 * lease expired goes back to QUEUED while attempts remain. {@code app.scheduling.enabled=false}
 * (the test profile) switches all scheduled work off.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "app.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

    private final ProcessingJobManager jobs;

    public SchedulingConfig(ProcessingJobManager jobs) {
        this.jobs = jobs;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT1M")
    public void recoverStaleJobs() {
        jobs.recoverStaleJobs(Instant.now());
    }
}
