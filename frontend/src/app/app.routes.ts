import { Routes } from '@angular/router';

/**
 * Top-level routes. Features own their child routes and are lazy-loaded —
 * the shell stays feature-agnostic.
 */
export const appRoutes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'jobs' },
  {
    path: 'jobs',
    loadChildren: () => import('./features/jobs/jobs.routes').then((m) => m.jobsRoutes),
  },
  {
    path: '**',
    title: 'Not found · QueueBoard',
    loadComponent: () => import('./core/not-found/not-found').then((m) => m.NotFoundPage),
  },
];
