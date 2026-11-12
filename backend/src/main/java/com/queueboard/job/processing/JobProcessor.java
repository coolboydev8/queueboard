package com.queueboard.job.processing;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.queueboard.config.ProcessingProperties;
import com.queueboard.job.domain.Job;
import com.queueboard.job.domain.JobService;

/**
 * Simulated background worker (ADR-0004). On a fixed delay it claims the oldest
 * {@code PENDING} jobs — via {@link JobService}, so the state machine and optimistic
 * locking apply — hands each to a dedicated bounded executor, simulates work for a
 * configured duration <em>outside any transaction</em>, and completes the job as
 * {@code SUCCEEDED} or {@code FAILED} in a fresh short transaction.
 *
 * <p>Failure is deterministic via payload markers ({@link ProcessingProperties}), so
 * demos and tests can force it. A lost claim race
 * ({@link OptimisticLockingFailureException}) means another actor won — skip, never
 * double-process. Any crash during simulated work marks the job {@code FAILED};
 * a job is never left stuck in {@code PROCESSING}.
 *
 * <p>Disable with {@code queueboard.processing.enabled=false} (deterministic tests).
 */
@Component
@ConditionalOnBooleanProperty(name = "queueboard.processing.enabled", matchIfMissing = true)
public class JobProcessor {

    private static final Logger log = LoggerFactory.getLogger(JobProcessor.class);

    private final JobService jobService;
    private final ProcessingProperties properties;
    private final TaskExecutor executor;
    private final AtomicInteger inFlight = new AtomicInteger();

    public JobProcessor(JobService jobService,
                        ProcessingProperties properties,
                        @Qualifier("jobProcessingExecutor") TaskExecutor executor) {
        this.jobService = jobService;
        this.properties = properties;
        this.executor = executor;
    }

    /** Claim loop: take waiting jobs while a worker is free, then wait for the next tick. */
    @Scheduled(fixedDelayString = "${queueboard.processing.poll-interval}")
    public void poll() {
        while (inFlight.get() < properties.workerConcurrency()) {
            Optional<Job> claimed = tryClaim();
            if (claimed.isEmpty()) {
                return; // queue drained, or a race was lost — try again next tick
            }
            Job job = claimed.get();
            inFlight.incrementAndGet();
            try {
                executor.execute(() -> process(job));
            } catch (RejectedExecutionException e) {
                // The capacity guard said go but the pool refused (e.g. shutting down).
                // Fail the job rather than leave it stuck in PROCESSING.
                inFlight.decrementAndGet();
                safeFail(job.getId(), "No worker capacity available");
                return;
            }
        }
    }

    private Optional<Job> tryClaim() {
        try {
            return jobService.claimNextPending();
        } catch (OptimisticLockingFailureException e) {
            log.debug("Lost a claim race; skipping this tick");
            return Optional.empty();
        }
    }

    /** Runs on a worker thread with a detached snapshot; attempts is already incremented. */
    private void process(Job claimed) {
        UUID id = claimed.getId();
        try {
            simulateWork();
            if (shouldFail(claimed)) {
                jobService.fail(id, failureMessage(claimed));
            } else {
                jobService.succeed(id);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            safeFail(id, "Processing was interrupted");
        } catch (RuntimeException e) {
            safeFail(id, "Processing error: " + e.getMessage());
        } finally {
            inFlight.decrementAndGet();
        }
    }

    private void simulateWork() throws InterruptedException {
        long minMillis = properties.minDuration().toMillis();
        long maxMillis = Math.max(minMillis, properties.maxDuration().toMillis());
        Thread.sleep(ThreadLocalRandom.current().nextLong(minMillis, maxMillis + 1));
    }

    /** Flaky is checked before fail, so a payload containing both behaves as flaky. */
    private boolean shouldFail(Job job) {
        String payload = job.getPayload() == null ? "" : job.getPayload();
        if (payload.contains(properties.flakyMarker())) {
            return job.getAttempts() == 1; // only the first attempt fails — a retry recovers
        }
        return payload.contains(properties.failMarker());
    }

    private String failureMessage(Job job) {
        String payload = job.getPayload() == null ? "" : job.getPayload();
        String marker = payload.contains(properties.flakyMarker())
                ? properties.flakyMarker()
                : properties.failMarker();
        return "Simulated failure (attempt %d): payload contains '%s'"
                .formatted(job.getAttempts(), marker);
    }

    private void safeFail(UUID id, String message) {
        try {
            jobService.fail(id, message);
        } catch (RuntimeException e) {
            log.error("Could not mark job {} as FAILED; it may be stuck in PROCESSING", id, e);
        }
    }
}
