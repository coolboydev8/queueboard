import { Routes } from '@angular/router';

import { JobsStore } from './data-access/jobs.store';
import { JobCreatePage } from './pages/job-create/job-create';
import { JobDetailPage } from './pages/job-detail/job-detail';
import { JobListPage } from './pages/job-list/job-list';

/**
 * Jobs feature routes, lazy-loaded from the shell.
 * The componentless parent owns JobsStore so list, create, and detail share it.
 * `new` is declared before `:id` so it is not captured as a job id.
 */
export const jobsRoutes: Routes = [
  {
    path: '',
    providers: [JobsStore],
    children: [
      { path: '', component: JobListPage, title: 'Jobs · QueueBoard' },
      { path: 'new', component: JobCreatePage, title: 'New job · QueueBoard' },
      { path: ':id', component: JobDetailPage, title: 'Job · QueueBoard' },
    ],
  },
];
