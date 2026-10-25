package com.ielop.platform.assistant;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Runs queued {@code AI_SUMMARY} jobs every 2 s. Off when {@code app.ai.worker-enabled=false} (tests drive {@link SummaryService}). */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = "worker-enabled", havingValue = "true", matchIfMissing = true)
public class SummaryWorker {

    private final SummaryService summaries;
    private final String workerId = "ai-summary-" + UUID.randomUUID();

    public SummaryWorker(SummaryService summaries) {
        this.summaries = summaries;
    }

    @Scheduled(initialDelayString = "PT15S", fixedDelayString = "PT2S")
    public void work() {
        while (summaries.runNext(workerId).isPresent()) {
            // One job at a time; drain the queue.
        }
    }
}
