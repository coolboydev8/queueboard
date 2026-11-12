import { ChangeDetectionStrategy, Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';

import { Timestamp } from '../../../../shared/ui/timestamp/timestamp';
import { STATUS_LABELS, type JobView } from '../../models/job.model';
import { JobStatusBadge } from '../job-status-badge/job-status-badge';

/** One table row. The name is the single link; it stretches across the row. */
@Component({
  selector: 'tr[qbJobRow]',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, JobStatusBadge, Timestamp],
  templateUrl: './job-row.html',
  styleUrl: './job-row.css',
})
export class JobRow {
  readonly job = input.required<JobView>();

  statusLabel(): string {
    return STATUS_LABELS[this.job().status];
  }
}
