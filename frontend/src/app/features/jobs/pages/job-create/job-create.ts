import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { Router, RouterLink } from '@angular/router';

import { AlertBanner } from '../../../../shared/ui/alert-banner/alert-banner';
import { JobsStore } from '../../data-access/jobs.store';
import { type CreateJobRequest } from '../../models/job.model';
import { JobForm } from '../../ui/job-form/job-form';

@Component({
  selector: 'qb-job-create',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, AlertBanner, JobForm],
  templateUrl: './job-create.html',
  styleUrl: './job-create.css',
})
export class JobCreatePage {
  readonly store = inject(JobsStore);
  private readonly router = inject(Router);

  constructor() {
    this.store.clearCreateError();
  }

  async onSubmit(request: CreateJobRequest): Promise<void> {
    try {
      const job = await this.store.create(request);
      await this.router.navigate(['/jobs', job.id]);
    } catch {
      // createError is set on the store and mapped onto the form.
    }
  }
}
