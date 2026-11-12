import { ChangeDetectionStrategy, Component, DestroyRef, effect, inject, input, signal, untracked } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AlertBanner } from '../../../../shared/ui/alert-banner/alert-banner';
import { EmptyState } from '../../../../shared/ui/empty-state/empty-state';
import { ProgressIndicator } from '../../../../shared/ui/progress-indicator/progress-indicator';
import { Timestamp } from '../../../../shared/ui/timestamp/timestamp';
import { JobsStore } from '../../data-access/jobs.store';
import { STATUS_LABELS, type DisplayStatus } from '../../models/job.model';
import { JobStatusBadge } from '../../ui/job-status-badge/job-status-badge';
import { LifecycleTrack } from '../../ui/lifecycle-track/lifecycle-track';
import { RetryButton } from '../../ui/retry-button/retry-button';

@Component({
  selector: 'qb-job-detail',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, AlertBanner, EmptyState, ProgressIndicator, Timestamp, LifecycleTrack, JobStatusBadge, RetryButton],
  templateUrl: './job-detail.html',
  styleUrl: './job-detail.css',
})
export class JobDetailPage {
  readonly id = input.required<string>();
  readonly store = inject(JobsStore);
  readonly liveMessage = signal('');

  private seen: { id: string; status: DisplayStatus } | null = null;

  constructor() {
    effect(() => {
      const id = this.id();
      untracked(() => this.store.select(id));
    });

    effect(() => {
      const job = this.store.selected();
      if (!job) {
        return;
      }
      const previous = this.seen;
      this.seen = { id: job.id, status: job.status };
      if (previous && previous.id === job.id && previous.status !== job.status) {
        this.liveMessage.set(`${job.name} is now ${STATUS_LABELS[job.status]}.`);
      }
    });

    inject(DestroyRef).onDestroy(() => this.store.clearSelection());
  }

  async onRetry(): Promise<void> {
    try {
      await this.store.retry(this.id());
    } catch {
      // The store keeps the server's error for the banner.
    }
  }
}
