import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { type DisplayStatus } from '../../models/job.model';

/**
 * Rendered only for FAILED. The click asks the server; it does not
 * decide that a retry is allowed.
 */
@Component({
  selector: 'qb-retry-button',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './retry-button.html',
  styleUrl: './retry-button.css',
})
export class RetryButton {
  readonly status = input.required<DisplayStatus>();
  readonly pending = input(false);
  readonly retried = output<void>();
}
