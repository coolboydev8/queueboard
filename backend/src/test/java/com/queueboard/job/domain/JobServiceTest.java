package com.queueboard.job.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.AdditionalAnswers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import com.queueboard.config.ProcessingProperties;
import com.queueboard.job.domain.exception.JobNotFoundException;
import com.queueboard.job.domain.exception.RetryNotAllowedException;
import com.queueboard.job.persistence.JobRepository;

/**
 * Unit tests (JUnit 5 + Mockito) for business rules with the repository mocked.
 * The full retry decision table lives here; persistence is exercised in the
 * integration test, not trusted to mocks.
 */
@ExtendWith(MockitoExtension.class)
class JobServiceTest {

    private static final int MAX_ATTEMPTS = 3;

    @Mock
    private JobRepository repository;

    private JobService service;

    @BeforeEach
    void setUp() {
        ProcessingProperties properties = new ProcessingProperties(
                Duration.ofSeconds(1), Duration.ofMillis(10), Duration.ofMillis(20),
                MAX_ATTEMPTS, "fail", "flaky", 2);
        service = new JobService(repository, properties);
        // Lenient: not every test saves, and strict stubbing should still police the rest.
        Mockito.lenient().when(repository.save(any(Job.class)))
                .thenAnswer(AdditionalAnswers.returnsFirstArg());
        Mockito.lenient().when(repository.saveAndFlush(any(Job.class)))
                .thenAnswer(AdditionalAnswers.returnsFirstArg());
    }

    // ---- create / read --------------------------------------------------------------

    @Test
    void create_returnsPendingJob_withStampedCreatedAt_andZeroAttempts() {
        Job job = service.create("nightly-report", "report", "{}");

        assertThat(job.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(job.getAttempts()).isZero();
        assertThat(job.getCreatedAt()).isNotNull();
        verify(repository).save(any(Job.class));
    }

    @Test
    void getById_unknownId_throwsJobNotFound() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatExceptionOfType(JobNotFoundException.class)
                .isThrownBy(() -> service.getById(id))
                .withMessageContaining(id.toString());
    }

    @Test
    void findAll_withoutStatus_returnsNewestFirst() {
        Job job = new Job("a", "echo", null);
        when(repository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(job));

        assertThat(service.findAll(null)).containsExactly(job);
    }

    @Test
    void findAll_withStatus_usesTheFilteredQuery() {
        Job job = new Job("a", "echo", null);
        when(repository.findByStatusOrderByCreatedAtDesc(JobStatus.FAILED)).thenReturn(List.of(job));

        assertThat(service.findAll(JobStatus.FAILED)).containsExactly(job);
        verify(repository, never()).findAllByOrderByCreatedAtDesc();
    }

    // ---- retry decision table -------------------------------------------------------

    @Test
    void retry_fromFailedBelowMaxAttempts_requeuesToPending() {
        Job job = failedJob(1);
        givenFound(job);

        Job result = service.retry(job.getId());

        assertThat(result.getStatus()).isEqualTo(JobStatus.PENDING);
        verify(repository).save(job);
    }

    @Test
    void retry_fromFailedWithAttemptsExhausted_throwsRetryNotAllowed() {
        Job job = failedJob(MAX_ATTEMPTS);
        givenFound(job);

        assertThatExceptionOfType(RetryNotAllowedException.class)
                .isThrownBy(() -> service.retry(job.getId()))
                .withMessageContaining("exhausted");
        verify(repository, never()).save(any(Job.class));
    }

    @Test
    void retry_pendingWithPriorAttempts_isAnIdempotentNoOp() {
        Job job = failedJob(1);
        job.retry(MAX_ATTEMPTS); // already re-queued by an earlier retry
        givenFound(job);

        Job result = service.retry(job.getId());

        assertThat(result.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(result.getAttempts()).isEqualTo(1);
        verify(repository, never()).save(any(Job.class));
    }

    @Test
    void retry_pendingNeverAttempted_throwsRetryNotAllowed() {
        Job job = new Job("fresh", "echo", null);
        givenFound(job);

        assertThatExceptionOfType(RetryNotAllowedException.class)
                .isThrownBy(() -> service.retry(job.getId()))
                .withMessageContaining("never been attempted");
    }

    @Test
    void retry_whileProcessing_throwsRetryNotAllowed() {
        Job job = new Job("busy", "echo", null);
        job.markProcessing();
        givenFound(job);

        assertThatExceptionOfType(RetryNotAllowedException.class)
                .isThrownBy(() -> service.retry(job.getId()))
                .withMessageContaining("currently processing");
    }

    @Test
    void retry_afterSuccess_throwsRetryNotAllowed() {
        Job job = new Job("done", "echo", null);
        job.markProcessing();
        job.markSucceeded();
        givenFound(job);

        assertThatExceptionOfType(RetryNotAllowedException.class)
                .isThrownBy(() -> service.retry(job.getId()))
                .withMessageContaining("succeeded");
    }

    // ---- processor hooks ------------------------------------------------------------

    @Test
    void claimNextPending_emptyQueue_returnsEmpty() {
        when(repository.findFirstByStatusOrderByCreatedAtAsc(JobStatus.PENDING))
                .thenReturn(Optional.empty());

        assertThat(service.claimNextPending()).isEmpty();
        verify(repository, never()).saveAndFlush(any(Job.class));
    }

    @Test
    void claimNextPending_marksTheOldestPendingJobProcessing() {
        Job job = new Job("oldest", "echo", null);
        when(repository.findFirstByStatusOrderByCreatedAtAsc(JobStatus.PENDING))
                .thenReturn(Optional.of(job));
        givenFound(job);

        Optional<Job> claimed = service.claimNextPending();

        assertThat(claimed).isPresent();
        assertThat(claimed.get().getStatus()).isEqualTo(JobStatus.PROCESSING);
        assertThat(claimed.get().getAttempts()).isEqualTo(1);
        verify(repository).saveAndFlush(job); // flush forces the version check inside the call
    }

    @Test
    void fail_capturesTheErrorMessage() {
        Job job = new Job("doomed", "echo", null);
        job.markProcessing();
        givenFound(job);

        Job result = service.fail(job.getId(), "simulated failure");

        assertThat(result.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(result.getErrorMessage()).isEqualTo("simulated failure");
        assertThat(result.getCompletedAt()).isNotNull();
    }

    @Test
    void succeed_marksTheJobSucceeded() {
        Job job = new Job("fine", "echo", null);
        job.markProcessing();
        givenFound(job);

        Job result = service.succeed(job.getId());

        assertThat(result.getStatus()).isEqualTo(JobStatus.SUCCEEDED);
        assertThat(result.getErrorMessage()).isNull();
    }

    // ---- helpers --------------------------------------------------------------------

    /** The entity's id is database-generated; unit tests look jobs up by whatever it is (null included). */
    private void givenFound(Job job) {
        when(repository.findById(job.getId())).thenReturn(Optional.of(job));
    }

    private Job failedJob(int attempts) {
        Job job = new Job("flaky-import", "import", "flaky");
        for (int i = 0; i < attempts; i++) {
            job.markProcessing();
            job.markFailed("boom " + (i + 1));
            if (i < attempts - 1) {
                job.retry(MAX_ATTEMPTS + 1); // allow building states beyond the service rule
            }
        }
        return job;
    }
}
