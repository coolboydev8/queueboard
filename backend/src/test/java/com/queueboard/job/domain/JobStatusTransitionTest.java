package com.queueboard.job.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.queueboard.job.domain.exception.InvalidJobTransitionException;
import com.queueboard.job.domain.exception.RetryNotAllowedException;

/**
 * Pure unit tests for the state machine — bottom of the pyramid, fast, exhaustive.
 * The matrix below is an independent restatement of the spec (ADR-0003), not derived
 * from the production table.
 */
class JobStatusTransitionTest {

    /** All 16 cells, written out: exactly four transitions are legal. */
    static Stream<Arguments> fullMatrix() {
        return Stream.of(
                Arguments.of(JobStatus.PENDING, JobStatus.PENDING, false),
                Arguments.of(JobStatus.PENDING, JobStatus.PROCESSING, true),
                Arguments.of(JobStatus.PENDING, JobStatus.SUCCEEDED, false),
                Arguments.of(JobStatus.PENDING, JobStatus.FAILED, false),
                Arguments.of(JobStatus.PROCESSING, JobStatus.PENDING, false),
                Arguments.of(JobStatus.PROCESSING, JobStatus.PROCESSING, false),
                Arguments.of(JobStatus.PROCESSING, JobStatus.SUCCEEDED, true),
                Arguments.of(JobStatus.PROCESSING, JobStatus.FAILED, true),
                Arguments.of(JobStatus.SUCCEEDED, JobStatus.PENDING, false),
                Arguments.of(JobStatus.SUCCEEDED, JobStatus.PROCESSING, false),
                Arguments.of(JobStatus.SUCCEEDED, JobStatus.SUCCEEDED, false),
                Arguments.of(JobStatus.SUCCEEDED, JobStatus.FAILED, false),
                Arguments.of(JobStatus.FAILED, JobStatus.PENDING, true),
                Arguments.of(JobStatus.FAILED, JobStatus.PROCESSING, false),
                Arguments.of(JobStatus.FAILED, JobStatus.SUCCEEDED, false),
                Arguments.of(JobStatus.FAILED, JobStatus.FAILED, false));
    }

    @ParameterizedTest(name = "{0} -> {1} allowed: {2}")
    @MethodSource("fullMatrix")
    void canTransitionTo_matchesTheSpec(JobStatus from, JobStatus to, boolean allowed) {
        assertThat(from.canTransitionTo(to)).isEqualTo(allowed);
    }

    @ParameterizedTest
    @EnumSource(JobStatus.class)
    void isTerminal_trueOnlyForSucceededAndFailed(JobStatus status) {
        boolean expected = status == JobStatus.SUCCEEDED || status == JobStatus.FAILED;
        assertThat(status.isTerminal()).isEqualTo(expected);
    }

    /**
     * Entity-level enforcement: the transition methods apply the same table and stamp
     * the audit trail. States are reached via legal calls only — the entity has no setters.
     */
    @Nested
    class EntityTransitions {

        private Job newJob() {
            return new Job("report-42", "report", "some payload");
        }

        private Job processingJob() {
            Job job = newJob();
            job.markProcessing();
            return job;
        }

        private Job failedJob() {
            Job job = processingJob();
            job.markFailed("boom");
            return job;
        }

        @Test
        void newJob_startsPending_withCreatedAtAndZeroAttempts() {
            Job job = newJob();
            assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
            assertThat(job.getAttempts()).isZero();
            assertThat(job.getCreatedAt()).isNotNull();
            assertThat(job.getStartedAt()).isNull();
            assertThat(job.getCompletedAt()).isNull();
            assertThat(job.getErrorMessage()).isNull();
        }

        @Test
        void markProcessing_incrementsAttempts_stampsStartedAt_clearsErrorAndCompleted() {
            Job job = failedJob();
            job.retry(3);

            job.markProcessing();

            assertThat(job.getStatus()).isEqualTo(JobStatus.PROCESSING);
            assertThat(job.getAttempts()).isEqualTo(2);
            assertThat(job.getStartedAt()).isNotNull();
            assertThat(job.getCompletedAt()).isNull();
            assertThat(job.getErrorMessage()).as("a new attempt clears the old failure").isNull();
        }

        @Test
        void markSucceeded_stampsCompletedAt_andClearsError() {
            Job job = processingJob();

            job.markSucceeded();

            assertThat(job.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
            assertThat(job.getCompletedAt()).isNotNull();
            assertThat(job.getErrorMessage()).isNull();
        }

        @Test
        void markFailed_capturesErrorAndCompletedAt() {
            Job job = processingJob();

            job.markFailed("simulated failure");

            assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
            assertThat(job.getErrorMessage()).isEqualTo("simulated failure");
            assertThat(job.getCompletedAt()).isNotNull();
        }

        @Test
        void markFailed_withBlankError_capturesAFallbackMessage() {
            Job job = processingJob();

            job.markFailed("  ");

            assertThat(job.getErrorMessage()).isEqualTo("Unknown error");
        }

        @Test
        void retry_belowMaxAttempts_returnsToPending_keepsErrorMessage_clearsTimestamps() {
            Job job = failedJob();

            job.retry(3);

            assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
            assertThat(job.getErrorMessage())
                    .as("the last failure stays visible until the next attempt starts")
                    .isEqualTo("boom");
            assertThat(job.getStartedAt()).isNull();
            assertThat(job.getCompletedAt()).isNull();
            assertThat(job.getAttempts()).isEqualTo(1);
        }

        @Test
        void retry_atMaxAttempts_throwsRetryNotAllowed() {
            Job job = failedJob();

            assertThatExceptionOfType(RetryNotAllowedException.class)
                    .isThrownBy(() -> job.retry(1))
                    .withMessageContaining("exhausted");
            assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        }

        static Stream<Arguments> illegalCalls() {
            Consumer<Job> markProcessing = Job::markProcessing;
            Consumer<Job> markSucceeded = Job::markSucceeded;
            Consumer<Job> markFailed = job -> job.markFailed("x");
            Consumer<Job> retry = job -> job.retry(3);
            return Stream.of(
                    Arguments.of("markSucceeded on PENDING", State.PENDING, markSucceeded),
                    Arguments.of("markFailed on PENDING", State.PENDING, markFailed),
                    Arguments.of("retry on fresh PENDING", State.PENDING, retry),
                    Arguments.of("markProcessing on PROCESSING", State.PROCESSING, markProcessing),
                    Arguments.of("retry on PROCESSING", State.PROCESSING, retry),
                    Arguments.of("markProcessing on SUCCEEDED", State.SUCCEEDED, markProcessing),
                    Arguments.of("markFailed on SUCCEEDED", State.SUCCEEDED, markFailed),
                    Arguments.of("retry on SUCCEEDED", State.SUCCEEDED, retry),
                    Arguments.of("markSucceeded on FAILED", State.FAILED, markSucceeded),
                    Arguments.of("markFailed on FAILED", State.FAILED, markFailed),
                    Arguments.of("markProcessing on FAILED", State.FAILED, markProcessing));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("illegalCalls")
        void illegalEntityTransitions_throw(String description, State state, Consumer<Job> call) {
            Job job = state.build();
            JobStatus before = job.getStatus();

            assertThatCode(() -> call.accept(job))
                    .isInstanceOf(InvalidJobTransitionException.class);
            assertThat(job.getStatus()).as("state is unchanged after a rejected call").isEqualTo(before);
        }

        enum State {
            PENDING {
                Job build() {
                    return new Job("n", "t", null);
                }
            },
            PROCESSING {
                Job build() {
                    Job job = PENDING.build();
                    job.markProcessing();
                    return job;
                }
            },
            SUCCEEDED {
                Job build() {
                    Job job = PROCESSING.build();
                    job.markSucceeded();
                    return job;
                }
            },
            FAILED {
                Job build() {
                    Job job = PROCESSING.build();
                    job.markFailed("boom");
                    return job;
                }
            };

            abstract Job build();
        }
    }
}
