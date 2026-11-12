package com.queueboard.job.domain;

/**
 * Job lifecycle states. Allowed transitions are declared here — the single source of
 * truth for the state machine (ADR-0003):
 *
 * <pre>
 *   PENDING ──claim──▶ PROCESSING ──▶ SUCCEEDED (terminal)
 *      ▲                   │
 *      └────retry──── FAILED (terminal unless retry rules permit)
 * </pre>
 *
 * The full 4×4 matrix has exactly four legal cells; every self-transition is illegal.
 * {@code FAILED → PENDING} is a legal <em>edge</em>, but taking it additionally requires
 * an explicit retry request within the max-attempts rule — that gate lives in
 * {@link Job#retry(int)} / {@link JobService}, beside the state machine, not in a controller.
 */
public enum JobStatus {
    PENDING,
    PROCESSING,
    SUCCEEDED,
    FAILED;

    /** Whether the lifecycle permits moving from this state to {@code target}. */
    public boolean canTransitionTo(JobStatus target) {
        return switch (this) {
            case PENDING -> target == PROCESSING;
            case PROCESSING -> target == SUCCEEDED || target == FAILED;
            case FAILED -> target == PENDING; // retry edge; gated further by retry rules
            case SUCCEEDED -> false;
        };
    }

    /**
     * Terminal means the worker takes no further action without an external command.
     * {@code FAILED} is terminal in that sense even though the retry edge exists.
     */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
