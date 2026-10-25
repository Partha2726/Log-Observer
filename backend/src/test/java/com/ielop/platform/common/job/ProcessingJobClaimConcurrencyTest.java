package com.ielop.platform.common.job;

import com.ielop.platform.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JOB-001: two workers never claim the same job. Worker A keeps its claim
 * transaction open (so its row lock is held) while worker B claims; with
 * {@code FOR UPDATE SKIP LOCKED} B must skip A's row and take the next job
 * instead of blocking or double-claiming.
 */
class ProcessingJobClaimConcurrencyTest extends IntegrationTest {

    private static final String TYPE = "JOB001_TEST";

    @Autowired
    private ProcessingJobManager jobManager;
    @Autowired
    private ProcessingJobRepository jobRepository;
    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void clean() {
        jobRepository.deleteAll();
    }

    @Test
    void concurrentWorkersClaimDifferentJobs() throws Exception {
        jobManager.createJob(TYPE, "payload://1", 3);
        jobManager.createJob(TYPE, "payload://2", 3);

        CountDownLatch aHoldsLock = new CountDownLatch(1);
        CountDownLatch bDone = new CountDownLatch(1);

        CompletableFuture<UUID> workerA = CompletableFuture.supplyAsync(() -> tx.execute(status -> {
            UUID id = jobManager.claimNextJob(TYPE, "worker-a", Duration.ofMinutes(5)).orElseThrow().getId();
            aHoldsLock.countDown();
            await(bDone);
            return id;
        }));

        await(aHoldsLock);
        Optional<ProcessingJob> claimedByB = jobManager.claimNextJob(TYPE, "worker-b", Duration.ofMinutes(5));
        bDone.countDown();
        UUID claimedByA = workerA.get(30, TimeUnit.SECONDS);

        assertThat(claimedByB).isPresent();
        assertThat(claimedByB.get().getId()).isNotEqualTo(claimedByA);
        assertThat(jobRepository.findAll()).allMatch(j -> j.getStatus() == ProcessingJobStatus.RUNNING);
    }

    @Test
    void aLockedJobIsSkippedWhenNoOtherJobIsQueued() throws Exception {
        jobManager.createJob(TYPE, "payload://only", 3);

        CountDownLatch aHoldsLock = new CountDownLatch(1);
        CountDownLatch bDone = new CountDownLatch(1);

        CompletableFuture<UUID> workerA = CompletableFuture.supplyAsync(() -> tx.execute(status -> {
            UUID id = jobManager.claimNextJob(TYPE, "worker-a", Duration.ofMinutes(5)).orElseThrow().getId();
            aHoldsLock.countDown();
            await(bDone);
            return id;
        }));

        await(aHoldsLock);
        Optional<ProcessingJob> claimedByB = jobManager.claimNextJob(TYPE, "worker-b", Duration.ofMinutes(5));
        bDone.countDown();
        workerA.get(30, TimeUnit.SECONDS);

        assertThat(claimedByB).isEmpty();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
