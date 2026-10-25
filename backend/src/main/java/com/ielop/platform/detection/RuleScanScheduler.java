package com.ielop.platform.detection;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Queues a {@code RULE_SCAN} over the last {@code scanLookbackHours} every
 * {@code scanIntervalMinutes} (section 10), and runs queued scans. Off when
 * {@code app.rules.schedule-enabled=false} (tests drive {@link RuleScanService} directly); scheduling itself
 * is enabled by {@code common.job.SchedulingConfig}.
 */
@Configuration
@ConditionalOnProperty(prefix = "app.rules", name = "schedule-enabled", havingValue = "true", matchIfMissing = true)
public class RuleScanScheduler {

    private final RuleScanService scans;
    private final RulesProperties rules;
    private final Clock clock = Clock.systemUTC();
    private final String workerId = "rule-scan-" + UUID.randomUUID();

    public RuleScanScheduler(RuleScanService scans, RulesProperties rules) {
        this.scans = scans;
        this.rules = rules;
    }

    @Scheduled(initialDelayString = "PT1M", fixedDelayString = "PT${app.rules.scan-interval-minutes:5}M")
    public void queueScan() {
        Instant now = clock.instant();
        scans.request(now.minus(Duration.ofHours(rules.getScanLookbackHours())), now);
    }

    @Scheduled(initialDelayString = "PT20S", fixedDelayString = "PT10S")
    public void work() {
        while (scans.runNext(workerId).isPresent()) {
            // Drain the queue; each scan is its own job.
        }
    }
}
