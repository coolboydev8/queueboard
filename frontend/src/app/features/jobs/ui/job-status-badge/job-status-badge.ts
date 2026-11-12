import { ChangeDetectionStrategy, Component, input } from '@angular/core';

import { STATUS_LABELS, type DisplayStatus } from '../../models/job.model';

/**
 * One badge for every lifecycle state, including UNKNOWN.
 * Shape and text differ per state — color is not the only signal.
 * UNKNOWN is dashed and neutral, never the success treatment.
 */
@Component({
  selector: 'qb-job-status-badge',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './job-status-badge.html',
  styleUrl: './job-status-badge.css',
  host: {
    '[attr.data-status]': 'status()',
    '[class.badge--pending]': 'status() === "PENDING"',
    '[class.badge--processing]': 'status() === "PROCESSING"',
    '[class.badge--succeeded]': 'status() === "SUCCEEDED"',
    '[class.badge--failed]': 'status() === "FAILED"',
    '[class.badge--unknown]': 'status() === "UNKNOWN"',
  },
})
export class JobStatusBadge {
  readonly status = input.required<DisplayStatus>();

  label(): string {
    return STATUS_LABELS[this.status()];
  }
}
