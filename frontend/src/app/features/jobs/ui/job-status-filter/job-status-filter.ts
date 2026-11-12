import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';

import { type JobStatus } from '../../models/job.model';

interface StatusOption {
  readonly label: string;
  readonly value: JobStatus | null;
}

/**
 * Status filter as a real radio group, so arrow keys work without custom code.
 * "All" is null — UNKNOWN is not a filter the API accepts.
 */
@Component({
  selector: 'qb-job-status-filter',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './job-status-filter.html',
  styleUrl: './job-status-filter.css',
})
export class JobStatusFilter {
  readonly value = input<JobStatus | null>(null);
  readonly changed = output<JobStatus | null>();

  readonly options: readonly StatusOption[] = [
    { label: 'All', value: null },
    { label: 'Pending', value: 'PENDING' },
    { label: 'Processing', value: 'PROCESSING' },
    { label: 'Succeeded', value: 'SUCCEEDED' },
    { label: 'Failed', value: 'FAILED' },
  ];

  selected(option: JobStatus | null): boolean {
    return this.value() === option;
  }
}
