import { ApplicationRef, provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { ApiError } from '../../../core/http/api-error';
import { type Job } from '../models/job.model';
import { JOBS_POLL_INTERVAL, JobsStore } from './jobs.store';
import { JobsApi } from './jobs-api';

const POLL_MS = 40;

function sampleJob(overrides: Partial<Job> = {}): Job {
  return {
    id: '11111111-1111-4111-8111-111111111111',
    name: 'Nightly report',
    type: 'report',
    status: 'PENDING',
    attempts: 1,
    errorMessage: null,
    createdAt: '2026-11-09T15:04:00.000Z',
    startedAt: null,
    completedAt: null,
    version: 1,
    ...overrides,
  };
}

function failure(status: number, detail: string): ApiError {
  return new ApiError({
    kind: 'problem',
    status,
    title: status === 409 ? 'Not allowed' : 'Unavailable',
    detail,
    fieldErrors: [],
  });
}

describe('JobsStore', () => {
  let api: {
    list: ReturnType<typeof vi.fn>;
    get: ReturnType<typeof vi.fn>;
    create: ReturnType<typeof vi.fn>;
    retry: ReturnType<typeof vi.fn>;
  };

  beforeEach(() => {
    api = {
      list: vi.fn(() => of([sampleJob()])),
      get: vi.fn(() => of(sampleJob())),
      create: vi.fn(),
      retry: vi.fn(),
    };

    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        JobsStore,
        { provide: JobsApi, useValue: api },
        { provide: JOBS_POLL_INTERVAL, useValue: POLL_MS },
      ],
    });
  });

  it('keeps polling while a visible job is non-terminal and stops when all are terminal', async () => {
    let pending = true;
    api.list.mockImplementation(() =>
      of([
        sampleJob({
          status: pending ? 'PENDING' : 'SUCCEEDED',
          completedAt: pending ? null : '2026-11-09T15:06:00.000Z',
        }),
      ]),
    );

    const store = TestBed.inject(JobsStore);
    store.activateList();

    await waitUntil(() => (api.list.mock.calls.length as number) >= 2);
    pending = false;
    await waitUntil(() => store.jobs()?.[0]?.status === 'SUCCEEDED');
    await delay(POLL_MS);
    const callsAfterTerminal = api.list.mock.calls.length;
    await delay(POLL_MS * 4);
    expect(api.list.mock.calls.length).toBe(callsAfterTerminal);
  });

  it('exposes an unrecognized status as UNKNOWN, never SUCCEEDED', async () => {
    api.list.mockReturnValue(of([sampleJob({ status: 'DONE' })]));
    const store = TestBed.inject(JobsStore);
    store.activateList();

    await waitUntil(() => store.jobs()?.length === 1);
    const job = store.jobs()?.[0];
    expect(job?.status).toBe('UNKNOWN');
    expect(job?.status).not.toBe('SUCCEEDED');
    expect(job?.rawStatus).toBe('DONE');
  });

  it('keeps the previous list when a refresh fails and does not mark it fresh', async () => {
    let calls = 0;
    api.list.mockImplementation(() => {
      calls += 1;
      if (calls === 1) {
        return of([sampleJob()]);
      }
      return throwError(() => failure(503, 'Try again shortly.'));
    });

    const store = TestBed.inject(JobsStore);
    store.activateList();
    await waitUntil(() => store.listPhase() === 'ready');
    const freshAt = store.listLoadedAt();

    await waitUntil(() => store.listStale());
    expect(store.jobs()?.[0]?.name).toBe('Nightly report');
    expect(store.jobs()?.[0]?.status).toBe('PENDING');
    expect(store.listError()?.status).toBe(503);
    expect(store.listLoadedAt()).toBe(freshAt);
  });

  it('shows the retry response and does not invent a new status', async () => {
    const failed = sampleJob({
      status: 'FAILED',
      errorMessage: 'disk full',
      attempts: 2,
      version: 2,
    });
    const retried = sampleJob({
      status: 'PENDING',
      errorMessage: null,
      attempts: 2,
      version: 3,
    });
    api.get.mockReturnValue(of(failed));
    api.retry.mockReturnValue(of(retried));

    const store = TestBed.inject(JobsStore);
    store.select(failed.id);
    await waitUntil(() => store.selected()?.status === 'FAILED');

    const result = await store.retry(failed.id);
    expect(api.retry).toHaveBeenCalledWith(failed.id);
    expect(result).toMatchObject({ status: 'PENDING', attempts: 2, version: 3 });
    expect(store.selected()).toMatchObject({ status: 'PENDING', attempts: 2, version: 3 });

    await delay(POLL_MS * 2);
    expect(store.selected()?.status).toBe('PENDING');
    expect(store.selected()?.version).toBe(3);
  });

  it('treats a missing job as not found rather than an empty success', async () => {
    api.get.mockReturnValue(
      throwError(
        () =>
          new ApiError({
            kind: 'problem',
            status: 404,
            title: 'Not found',
            detail: 'No job with that id.',
            fieldErrors: [],
          }),
      ),
    );

    const store = TestBed.inject(JobsStore);
    store.select('missing-job');
    await waitUntil(() => store.detailPhase() === 'missing');

    expect(store.selected()).toBeNull();
    expect(store.detailError()?.status).toBe(404);
    expect(store.detailPhase()).not.toBe('ready');
  });

  it('keeps the failed job when the server refuses a retry', async () => {
    const failed = sampleJob({ status: 'FAILED', errorMessage: 'disk full', version: 2 });
    api.get.mockReturnValue(of(failed));
    api.retry.mockReturnValue(throwError(() => failure(409, 'Retry rules do not permit this job.')));

    const store = TestBed.inject(JobsStore);
    store.select(failed.id);
    await waitUntil(() => store.selected()?.status === 'FAILED');

    await expect(store.retry(failed.id)).rejects.toMatchObject({ status: 409 });
    expect(store.selected()?.status).toBe('FAILED');
    expect(store.retryError()?.detail).toBe('Retry rules do not permit this job.');
  });
});

async function waitUntil(predicate: () => boolean): Promise<void> {
  const started = Date.now();
  while (!predicate()) {
    TestBed.flushEffects();
    await TestBed.inject(ApplicationRef).whenStable();
    if (Date.now() - started > 2000) {
      throw new Error('timed out waiting for the store');
    }
    await delay(10);
  }
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
