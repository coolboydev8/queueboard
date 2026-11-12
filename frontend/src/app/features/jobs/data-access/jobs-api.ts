import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { CreateJobRequest, Job, JobStatus } from '../models/job.model';

/**
 * Thin HTTP client for the jobs endpoints — one method per OpenAPI operation.
 * No state and no business rules; those live in JobsStore.
 */
@Injectable({ providedIn: 'root' })
export class JobsApi {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/jobs';

  create(request: CreateJobRequest): Observable<Job> {
    return this.http.post<Job>(this.base, request);
  }

  list(status?: JobStatus): Observable<Job[]> {
    const params = status ? new HttpParams().set('status', status) : undefined;
    return this.http.get<Job[]>(this.base, { params });
  }

  get(id: string): Observable<Job> {
    return this.http.get<Job>(`${this.base}/${encodeURIComponent(id)}`);
  }

  retry(id: string): Observable<Job> {
    return this.http.post<Job>(`${this.base}/${encodeURIComponent(id)}/retry`, null);
  }
}
