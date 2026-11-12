package com.queueboard.job.domain;

import java.time.Instant;
import java.util.UUID;

import com.queueboard.job.domain.exception.InvalidJobTransitionException;
import com.queueboard.job.domain.exception.RetryNotAllowedException;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * The Job aggregate. Owns its state and enforces its own transitions — callers go
 * through the {@code mark*}/{@code retry} methods, never set status directly (there is
 * no status setter to misuse). Each transition validates against the
 * {@link JobStatus} table and stamps the minimal audit trail (ADR-0003).
 *
 * <p>Rules, stated once:
 * <ul>
 *   <li>{@code attempts} increments only in {@link #markProcessing()} — it counts
 *       <em>started</em> attempts, so it always names the attempt in flight or the
 *       last one completed.</li>
 *   <li>{@code createdAt} is stamped at construction and never changes;
 *       {@code startedAt} restamps on every claim; {@code completedAt} is non-null
 *       iff the status is terminal.</li>
 *   <li>Invariant powering idempotent retry: a {@code PENDING} job with
 *       {@code attempts > 0} can only exist via {@code FAILED → retry}, because
 *       attempts only grow on claim and {@code SUCCEEDED} has no outgoing edge.</li>
 * </ul>
 *
 * <p>Optimistic locking via {@link Version} protects races between the worker and
 * user actions (ADR-0004): the losing write fails loudly, never silently overwrites.
 */
@Entity
@Table(name = "jobs")
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 100)
    private String type;

    @Column(length = 10_000)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(length = 2_000)
    private String errorMessage;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    private Instant startedAt;

    private Instant completedAt;

    @Version
    private long version;

    /** JPA only. */
    protected Job() {
    }

    public Job(String name, String type, String payload) {
        this.name = name;
        this.type = type;
        this.payload = payload;
        this.status = JobStatus.PENDING;
        this.attempts = 0;
        this.createdAt = Instant.now();
    }

    /** Claim for processing: PENDING → PROCESSING. Starts a new attempt. */
    public void markProcessing() {
        transitionTo(JobStatus.PROCESSING);
        this.attempts++;
        this.startedAt = Instant.now();
        this.completedAt = null; // may be re-processing after a retry
        this.errorMessage = null; // a new attempt is underway — the old failure no longer stands
    }

    /** PROCESSING → SUCCEEDED. */
    public void markSucceeded() {
        transitionTo(JobStatus.SUCCEEDED);
        this.completedAt = Instant.now();
        this.errorMessage = null;
    }

    /** PROCESSING → FAILED, capturing the error for inspection. */
    public void markFailed(String error) {
        transitionTo(JobStatus.FAILED);
        this.completedAt = Instant.now();
        this.errorMessage = (error == null || error.isBlank()) ? "Unknown error" : error;
    }

    /**
     * FAILED → PENDING, permitted only while {@code attempts < maxAttempts}.
     * Keeps {@code errorMessage} on purpose: the board can still show why the last
     * attempt failed until the next attempt starts ({@link #markProcessing()} clears it).
     */
    public void retry(int maxAttempts) {
        if (status != JobStatus.FAILED) {
            throw new InvalidJobTransitionException(
                    "Job %s cannot be retried from %s".formatted(id, status));
        }
        if (attempts >= maxAttempts) {
            throw new RetryNotAllowedException(
                    "Job %s has exhausted its %d attempts".formatted(id, maxAttempts));
        }
        transitionTo(JobStatus.PENDING);
        this.startedAt = null;
        this.completedAt = null;
    }

    private void transitionTo(JobStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidJobTransitionException(
                    "Cannot transition job %s from %s to %s".formatted(id, status, target));
        }
        this.status = target;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public String getPayload() {
        return payload;
    }

    public JobStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public long getVersion() {
        return version;
    }
}
