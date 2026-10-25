package com.ielop.platform.trace;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Runs queued {@code BULK_REASSEMBLE} jobs. Off when {@code app.scheduling.enabled=false}. */
@Component
@ConditionalOnProperty(prefix = "app.scheduling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BulkReassembleWorker {

    private final BulkReassembleService bulk;
    private final String workerId = "bulk-reassemble-" + UUID.randomUUID();

    public BulkReassembleWorker(BulkReassembleService bulk) {
        this.bulk = bulk;
    }

    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT10S")
    public void work() {
        while (bulk.runNext(workerId).isPresent()) {
            // drain
        }
    }
}
