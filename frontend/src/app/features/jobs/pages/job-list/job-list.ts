import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';

import { AlertBanner } from '../../../../shared/ui/alert-banner/alert-banner';
import { EmptyState } from '../../../../shared/ui/empty-state/empty-state';
import { ProgressIndicator } from '../../../../shared/ui/progress-indicator/progress-indicator';
import { Timestamp } from '../../../../shared/ui/timestamp/timestamp';
import { JobsStore } from '../../data-access/jobs.store';
import { isJobStatus, type JobStatus } from '../../models/job.model';
import { JobRow } from '../../ui/job-row/job-row';
import { JobStatusFilter } from '../../ui/job-status-filter/job-status-filter';

@Component({
  selector: 'qb-job-list',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, AlertBanner, EmptyState, ProgressIndicator, Timestamp, JobStatusFilter, JobRow],
  templateUrl: './job-list.html',
  styleUrl: './job-list.css',
})
export class JobListPage {
  readonly store = inject(JobsStore);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  readonly loadedAtIso = computed(() => {
    const at = this.store.listLoadedAt();
    return at === null ? null : new Date(at).toISOString();
  });

  readonly staleMessage = computed(() => {
    const at = this.store.listLoadedAt();
    if (at === null) {
      return 'Showing the last jobs received. The latest refresh failed.';
    }
    const time = new Intl.DateTimeFormat(undefined, { timeStyle: 'short' }).format(new Date(at));
    return `Showing the last jobs received at ${time}. The latest refresh failed.`;
  });

  readonly emptyTitle = computed(() =>
    this.store.statusFilter() ? 'No jobs in this state' : 'No jobs yet',
  );

  readonly emptyMessage = computed(() =>
    this.store.statusFilter()
      ? 'Try another status, or submit a new job.'
      : 'Submit a job to see it move through pending, processing, and a final result.',
  );

  constructor() {
    this.store.activateList();
    inject(DestroyRef).onDestroy(() => this.store.deactivateList());

    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      const raw = params.get('status');
      const next = isJobStatus(raw) ? raw : null;
      if (next !== this.store.statusFilter()) {
        this.store.setStatusFilter(next);
      }
      if (raw && !isJobStatus(raw)) {
        void this.router.navigate([], {
          relativeTo: this.route,
          queryParams: { status: null },
          queryParamsHandling: 'merge',
          replaceUrl: true,
        });
      }
    });
  }

  onFilter(status: JobStatus | null): void {
    this.store.setStatusFilter(status);
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams: { status },
      queryParamsHandling: 'merge',
      replaceUrl: true,
    });
  }
}
