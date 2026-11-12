package com.queueboard.job.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;

import com.queueboard.config.ProcessingProperties;
import com.queueboard.job.domain.Job;
import com.queueboard.job.domain.JobService;

/**
 * Unit tests for the worker's claim/complete steps with {@link JobService} mocked.
 * A synchronous executor makes the handoff deterministic; a deferring executor
 * exercises the capacity guard.
 */
@ExtendWith(MockitoExtension.class)
class JobProcessorTest {

    @Mock
    private JobService jobService;

    private ProcessingProperties properties(int concurrency) {
        return new ProcessingProperties(
                Duration.ofMillis(50), Duration.ZERO, Duration.ZERO,
                3, "fail", "flaky", concurrency);
    }

    private JobProcessor synchronousProcessor(int concurrency) {
        return new JobProcessor(jobService, properties(concurrency), Runnable::run);
    }

    /** A claimed job: PROCESSING with the given attempt number. */
    private Job claimedJob(String payload, int attempts) {
        Job job = new Job("job", "echo", payload);
        for (int i = 0; i < attempts; i++) {
            job.markProcessing();
            if (i < attempts - 1) {
                job.markFailed("earlier attempt");
                job.retry(Integer.MAX_VALUE);
            }
        }
        return job;
    }

    @Test
    void poll_onEmptyQueue_doesNothing() {
        when(jobService.claimNextPending()).thenReturn(Optional.empty());

        synchronousProcessor(2).poll();

        verify(jobService, never()).succeed(any());
        verify(jobService, never()).fail(any(), anyString());
    }

    @Test
    void poll_whenTheClaimRaceIsLost_skipsWithoutThrowing() {
        when(jobService.claimNextPending())
                .thenThrow(new OptimisticLockingFailureException("another actor won"));

        JobProcessor processor = synchronousProcessor(2);

        assertThatCode(processor::poll).doesNotThrowAnyException();
        verify(jobService, never()).succeed(any());
        verify(jobService, never()).fail(any(), anyString());
    }

    @Test
    void poll_withANormalPayload_succeedsTheJobExactlyOnce() {
        Job job = claimedJob("just some payload", 1);
        when(jobService.claimNextPending())
                .thenReturn(Optional.of(job))
                .thenReturn(Optional.empty());

        synchronousProcessor(2).poll();

        verify(jobService, times(1)).succeed(job.getId());
        verify(jobService, never()).fail(any(), anyString());
        verify(jobService, times(2)).claimNextPending(); // each job claimed exactly once
    }

    @Test
    void poll_withTheFailMarker_failsTheJobWithAMessage() {
        Job job = claimedJob("please fail this one", 1);
        when(jobService.claimNextPending())
                .thenReturn(Optional.of(job))
                .thenReturn(Optional.empty());

        synchronousProcessor(2).poll();

        verify(jobService).fail(eq(job.getId()), contains("fail"));
        verify(jobService, never()).succeed(any());
    }

    @Test
    void flakyPayload_failsTheFirstAttempt_andSucceedsTheSecond() {
        Job firstAttempt = claimedJob("flaky import", 1);
        Job secondAttempt = claimedJob("flaky import", 2);
        when(jobService.claimNextPending())
                .thenReturn(Optional.of(firstAttempt))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(secondAttempt))
                .thenReturn(Optional.empty());

        JobProcessor processor = synchronousProcessor(2);
        processor.poll();
        processor.poll();

        verify(jobService).fail(eq(firstAttempt.getId()), contains("flaky"));
        verify(jobService).succeed(secondAttempt.getId());
    }

    @Test
    void aCrashWhileCompleting_marksTheJobFailed_insteadOfLeavingItStuck() {
        Job job = claimedJob("fine payload", 1);
        when(jobService.claimNextPending())
                .thenReturn(Optional.of(job))
                .thenReturn(Optional.empty());
        when(jobService.succeed(job.getId())).thenThrow(new IllegalStateException("db hiccup"));

        JobProcessor processor = synchronousProcessor(2);

        assertThatCode(processor::poll).doesNotThrowAnyException();
        verify(jobService).fail(any(), contains("db hiccup"));
    }

    @Test
    void poll_stopsClaimingOnceEveryWorkerIsBusy() {
        // An executor that accepts work but never runs it: in-flight stays occupied.
        List<Runnable> deferred = new ArrayList<>();
        JobProcessor processor = new JobProcessor(jobService, properties(1), deferred::add);
        when(jobService.claimNextPending())
                .thenReturn(Optional.of(claimedJob("first", 1)));

        processor.poll();
        processor.poll(); // next tick: the single worker is still busy

        verify(jobService, times(1)).claimNextPending();
        assertThat(deferred).hasSize(1);
    }
}
