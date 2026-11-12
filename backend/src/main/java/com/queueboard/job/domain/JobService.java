package com.queueboard.job.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.queueboard.config.ProcessingProperties;
import com.queueboard.job.domain.exception.JobNotFoundException;
import com.queueboard.job.domain.exception.RetryNotAllowedException;
import com.queueboard.job.persistence.JobRepository;

/**
 * Business rules for jobs — the only component that applies lifecycle transitions
 * (controllers stay thin; the processor delegates here too, ADR-0003).
 *
 * <p>Every public method is one short transaction. The processor calls
 * {@link #claimNextPending()}, {@link #succeed(UUID)} and {@link #fail(UUID, String)}
 * from outside any transaction, so the simulated sleep never holds a connection.
 */
@Service
public class JobService {

    private final JobRepository repository;
    private final ProcessingProperties properties;

    public JobService(JobRepository repository, ProcessingProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    /** Accepts a new job; it starts in {@code PENDING}. */
    @Transactional
    public Job create(String name, String type, String payload) {
        return repository.save(new Job(name, type, payload));
    }

    /** All jobs, newest first; {@code status == null} means no filter. */
    @Transactional(readOnly = true)
    public List<Job> findAll(JobStatus status) {
        return status == null
                ? repository.findAllByOrderByCreatedAtDesc()
                : repository.findByStatusOrderByCreatedAtDesc(status);
    }

    @Transactional(readOnly = true)
    public Job getById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new JobNotFoundException("No job with id " + id));
    }

    /**
     * Re-queues a failed job within the retry rules. Idempotent: retrying a job that
     * is already back in {@code PENDING} after a failure (recognizable by
     * {@code attempts > 0} — see the invariant on {@link Job}) is a no-op that returns
     * the current state instead of double-queuing it.
     *
     * @throws RetryNotAllowedException from any other state, or once attempts are exhausted
     */
    @Transactional
    public Job retry(UUID id) {
        Job job = getById(id);
        return switch (job.getStatus()) {
            case FAILED -> {
                job.retry(properties.maxAttempts()); // the attempts rule lives beside the state machine
                yield repository.save(job);
            }
            case PENDING -> {
                if (job.getAttempts() > 0) {
                    yield job; // already re-queued — idempotent replay, nothing to save
                }
                throw new RetryNotAllowedException(
                        "Job %s has never been attempted; it is already waiting".formatted(id));
            }
            case PROCESSING -> throw new RetryNotAllowedException(
                    "Job %s is currently processing".formatted(id));
            case SUCCEEDED -> throw new RetryNotAllowedException(
                    "Job %s already succeeded".formatted(id));
        };
    }

    // ---- processor hooks ------------------------------------------------------------

    /**
     * Finds the oldest {@code PENDING} job and transitions it to {@code PROCESSING}
     * in one transaction, so every lost race surfaces to the caller as a single
     * {@code OptimisticLockingFailureException} — never a half-claimed job.
     */
    @Transactional
    public Optional<Job> claimNextPending() {
        return repository.findFirstByStatusOrderByCreatedAtAsc(JobStatus.PENDING)
                .map(job -> start(job.getId()));
    }

    /** PENDING → PROCESSING. Flushes so a version conflict surfaces here, catchably. */
    @Transactional
    public Job start(UUID id) {
        Job job = getById(id);
        job.markProcessing();
        return repository.saveAndFlush(job);
    }

    /** PROCESSING → SUCCEEDED. */
    @Transactional
    public Job succeed(UUID id) {
        Job job = getById(id);
        job.markSucceeded();
        return repository.save(job);
    }

    /** PROCESSING → FAILED with the captured error message. */
    @Transactional
    public Job fail(UUID id, String error) {
        Job job = getById(id);
        job.markFailed(error);
        return repository.save(job);
    }
}
