import { Injectable, InjectionToken, type ResourceSnapshot, computed, effect, inject, resource, signal, untracked } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { EMPTY, firstValueFrom, interval, switchMap } from 'rxjs';

import { ApiError, asApiError } from '../../../core/http/api-error';
import {
  CreateJobRequest,
  Job,
  JobStatus,
  JobView,
  isTerminal,
  preferJob,
  toJobView,
} from '../models/job.model';
import { JobsApi } from './jobs-api';

/** How often a visible, non-terminal job is refreshed. Tests override this. */
export const JOBS_POLL_INTERVAL = new InjectionToken<number>('JOBS_POLL_INTERVAL', {
  providedIn: 'root',
  factory: () => 2000,
});

type LoadPhase = 'idle' | 'loading' | 'ready' | 'error';
type DetailPhase = LoadPhase | 'missing';

/**
 * Signal store for the jobs feature — the only stateful object in the feature.
 * Provided on the jobs route so the list and the detail page share one board.
 *
 * Every status on screen comes from a server response, narrowed through
 * {@link toJobView}. A failed refresh keeps the last payload and is marked stale
 * so it is never presented as fresh (ADR-0005).
 */
@Injectable()
export class JobsStore {
  private readonly api = inject(JobsApi);
  private readonly pollEveryMs = inject(JOBS_POLL_INTERVAL);

  private readonly listActive = signal(false);
  private readonly statusFilterState = signal<JobStatus | null>(null);
  private readonly listSnapshot = signal<JobView[] | null>(null);
  private readonly listFilterKey = signal<string | null>(null);
  private readonly listPhaseState = signal<LoadPhase>('idle');
  private readonly listErrorState = signal<ApiError | null>(null);
  private readonly listStaleState = signal(false);
  private readonly listLoadedAtState = signal<number | null>(null);

  private readonly selectedIdState = signal<string | null>(null);
  private readonly selectedJob = signal<JobView | null>(null);
  private readonly selectedKey = signal<string | null>(null);
  private readonly detailPhaseState = signal<DetailPhase>('idle');
  private readonly detailErrorState = signal<ApiError | null>(null);
  private readonly detailStaleState = signal(false);
  private readonly detailLoadedAtState = signal<number | null>(null);

  private readonly creatingState = signal(false);
  private readonly retryingState = signal(false);
  private readonly createErrorState = signal<ApiError | null>(null);
  private readonly retryErrorState = signal<ApiError | null>(null);

  /** `undefined` keeps the list resource idle until the list page is on screen. */
  private readonly listResource = resource({
    debugName: 'jobs-list',
    params: (): string | undefined =>
      this.listActive() ? (this.statusFilterState() ?? 'ALL') : undefined,
    loader: async ({ params }) => {
      const status = params === 'ALL' ? undefined : (params as JobStatus);
      const body = await firstValueFrom(this.api.list(status));
      if (!Array.isArray(body)) {
        throw unexpected('The job list could not be read.');
      }
      return body.map((job) => toJobView(job));
    },
  });

  private readonly detailResource = resource({
    debugName: 'job-detail',
    params: (): string | undefined => this.selectedIdState() ?? undefined,
    loader: async ({ params }) => {
      const body = await firstValueFrom(this.api.get(params));
      if (!body || typeof body !== 'object') {
        throw unexpected('The job could not be read.');
      }
      return toJobView(body as Job);
    },
  });

  readonly statusFilter = this.statusFilterState.asReadonly();
  readonly jobs = computed(() => {
    if (this.listPhaseState() === 'loading' || this.listPhaseState() === 'idle') {
      return null;
    }
    if (this.listFilterKey() !== (this.statusFilterState() ?? 'ALL')) {
      return null;
    }
    return this.listSnapshot();
  });
  readonly listPhase = this.listPhaseState.asReadonly();
  readonly listError = this.listErrorState.asReadonly();
  readonly listStale = this.listStaleState.asReadonly();
  readonly listLoadedAt = this.listLoadedAtState.asReadonly();
  readonly listRefreshing = computed(
    () => this.listPhaseState() === 'ready' && this.listResource.isLoading(),
  );

  readonly selected = computed(() => {
    const id = this.selectedIdState();
    const job = this.selectedJob();
    if (!id || !job || job.id !== id) {
      return null;
    }
    return job;
  });
  readonly detailPhase = this.detailPhaseState.asReadonly();
  readonly detailError = this.detailErrorState.asReadonly();
  readonly detailStale = this.detailStaleState.asReadonly();
  readonly detailLoadedAt = this.detailLoadedAtState.asReadonly();
  readonly detailRefreshing = computed(
    () => this.detailPhaseState() === 'ready' && this.detailResource.isLoading(),
  );

  readonly creating = this.creatingState.asReadonly();
  readonly retrying = this.retryingState.asReadonly();
  readonly createError = this.createErrorState.asReadonly();
  readonly retryError = this.retryErrorState.asReadonly();

  private readonly shouldPollList = computed(() => {
    if (!this.listActive()) {
      return false;
    }
    const jobs = this.jobs();
    return !!jobs?.some((job) => !isTerminal(job.status));
  });

  private readonly shouldPollDetail = computed(() => {
    const job = this.selected();
    return !!job && !isTerminal(job.status);
  });

  private readonly shouldPoll = computed(() => this.shouldPollList() || this.shouldPollDetail());

  constructor() {
    effect(() => {
      const status = this.listResource.status();
      const error = this.listResource.error();
      const value = this.listResource.hasValue() ? this.listResource.value() : undefined;
      const key = this.listActive() ? (this.statusFilterState() ?? 'ALL') : undefined;
      untracked(() => this.applyList(status, value, error, key));
    });

    effect(() => {
      const status = this.detailResource.status();
      const error = this.detailResource.error();
      const value = this.detailResource.hasValue() ? this.detailResource.value() : undefined;
      const id = this.selectedIdState() ?? undefined;
      untracked(() => this.applyDetail(status, value, error, id));
    });

    toObservable(this.shouldPoll)
      .pipe(
        switchMap((should) => (should ? interval(this.pollEveryMs) : EMPTY)),
        takeUntilDestroyed(),
      )
      .subscribe(() => {
        if (this.shouldPollList()) {
          this.listResource.reload();
        }
        if (this.shouldPollDetail()) {
          this.detailResource.reload();
        }
      });
  }

  activateList(): void {
    this.listActive.set(true);
  }

  deactivateList(): void {
    this.listActive.set(false);
  }

  setStatusFilter(status: JobStatus | null): void {
    this.statusFilterState.set(status);
  }

  select(id: string): void {
    if (this.selectedIdState() !== id) {
      this.retryErrorState.set(null);
    }
    this.selectedIdState.set(id);
  }

  clearSelection(): void {
    this.selectedIdState.set(null);
  }

  clearCreateError(): void {
    this.createErrorState.set(null);
  }

  reloadList(): void {
    this.listResource.reload();
  }

  reloadDetail(): void {
    this.detailResource.reload();
  }

  /** Returns the job the server accepted. Does not invent a list row. */
  async create(request: CreateJobRequest): Promise<JobView> {
    this.creatingState.set(true);
    this.createErrorState.set(null);
    try {
      return toJobView(await firstValueFrom(this.api.create(request)));
    } catch (error) {
      const apiError = asApiError(error);
      this.createErrorState.set(apiError);
      throw apiError;
    } finally {
      this.creatingState.set(false);
    }
  }

  /**
   * Sends a retry and shows the body the server returns.
   * A refusal (409) is an error, and the detail is reloaded so the screen
   * matches the server rather than the click.
   */
  async retry(id: string): Promise<JobView> {
    this.retryingState.set(true);
    this.retryErrorState.set(null);
    try {
      const job = toJobView(await firstValueFrom(this.api.retry(id)));
      if (this.selectedIdState() === id) {
        this.selectedJob.set(preferJob(this.selectedJob(), job));
        this.selectedKey.set(id);
        this.detailPhaseState.set('ready');
        this.detailStaleState.set(false);
        this.detailErrorState.set(null);
        this.detailLoadedAtState.set(Date.now());
      }
      return job;
    } catch (error) {
      const apiError = asApiError(error);
      this.retryErrorState.set(apiError);
      if (this.selectedIdState() === id) {
        this.detailResource.reload();
      }
      throw apiError;
    } finally {
      this.retryingState.set(false);
    }
  }

  private applyList(
    status: ResourceSnapshot<JobView[] | undefined>['status'],
    value: JobView[] | undefined,
    error: Error | undefined,
    key: string | undefined,
  ): void {
    if (key === undefined) {
      return;
    }

    if (status === 'resolved' || status === 'local') {
      this.listSnapshot.set(value ?? []);
      this.listFilterKey.set(key);
      this.listErrorState.set(null);
      this.listStaleState.set(false);
      this.listLoadedAtState.set(Date.now());
      this.listPhaseState.set('ready');
      return;
    }

    if (status === 'error') {
      const apiError = asApiError(error);
      this.listErrorState.set(apiError);
      if (this.listFilterKey() === key && this.listSnapshot() !== null) {
        this.listStaleState.set(true);
        this.listPhaseState.set('ready');
      } else {
        this.listStaleState.set(false);
        this.listPhaseState.set('error');
      }
      return;
    }

    if (this.listFilterKey() === key && this.listSnapshot() !== null) {
      this.listPhaseState.set('ready');
    } else {
      this.listPhaseState.set('loading');
    }
  }

  private applyDetail(
    status: ResourceSnapshot<JobView | undefined>['status'],
    value: JobView | undefined,
    error: Error | undefined,
    id: string | undefined,
  ): void {
    if (id === undefined) {
      return;
    }

    if (status === 'resolved' || status === 'local') {
      if (!value || value.id !== id) {
        return;
      }
      const current = this.selectedKey() === id ? this.selectedJob() : null;
      this.selectedJob.set(preferJob(current, value));
      this.selectedKey.set(id);
      this.detailErrorState.set(null);
      this.detailStaleState.set(false);
      this.detailLoadedAtState.set(Date.now());
      this.detailPhaseState.set('ready');
      return;
    }

    if (status === 'error') {
      const apiError = asApiError(error);
      this.detailErrorState.set(apiError);
      if (apiError.status === 404) {
        this.selectedJob.set(null);
        this.selectedKey.set(null);
        this.detailStaleState.set(false);
        this.detailPhaseState.set('missing');
        return;
      }
      if (this.selectedKey() === id && this.selectedJob()) {
        this.detailStaleState.set(true);
        this.detailPhaseState.set('ready');
        return;
      }
      this.detailStaleState.set(false);
      this.detailPhaseState.set('error');
      return;
    }

    if (this.selectedKey() === id && this.selectedJob()) {
      this.detailPhaseState.set('ready');
    } else {
      this.detailPhaseState.set('loading');
    }
  }
}

function unexpected(detail: string): ApiError {
  return new ApiError({
    kind: 'unexpected',
    status: 200,
    title: 'Unexpected response',
    detail,
    fieldErrors: [],
  });
}
