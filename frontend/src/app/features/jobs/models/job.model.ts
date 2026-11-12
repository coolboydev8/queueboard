/**
 * Frontend types mirroring the OpenAPI contract (docs/api/openapi.yaml).
 * The contract is the source of truth — change it there first.
 *
 * A status value the UI does not recognize is UNKNOWN, never success (ADR-0003).
 */

export const JOB_STATUSES = ['PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED'] as const;
export type JobStatus = (typeof JOB_STATUSES)[number];

export type DisplayStatus = JobStatus | 'UNKNOWN';

export const STATUS_LABELS: Record<DisplayStatus, string> = {
  PENDING: 'Pending',
  PROCESSING: 'Processing',
  SUCCEEDED: 'Succeeded',
  FAILED: 'Failed',
  UNKNOWN: 'Unknown',
};

export interface Job {
  id: string;
  name: string;
  type: string;
  payload?: string;
  status: string;
  attempts: number;
  errorMessage: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  version: number;
}

/** What the templates read. `status` has already been narrowed. */
export interface JobView {
  id: string;
  name: string;
  type: string;
  payload?: string;
  status: DisplayStatus;
  rawStatus: string;
  attempts: number;
  errorMessage: string | null;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  version: number;
}

export interface CreateJobRequest {
  name: string;
  type: string;
  payload?: string;
}

export function isJobStatus(value: string | null | undefined): value is JobStatus {
  return !!value && (JOB_STATUSES as readonly string[]).includes(value);
}

export function toDisplayStatus(value: string | null | undefined): DisplayStatus {
  return isJobStatus(value) ? value : 'UNKNOWN';
}

/** SUCCEEDED and FAILED are terminal. UNKNOWN is not — the server may still correct it. */
export function isTerminal(status: DisplayStatus): boolean {
  return status === 'SUCCEEDED' || status === 'FAILED';
}

export function toJobView(job: Job): JobView {
  const rawStatus = typeof job.status === 'string' ? job.status : '';
  return {
    id: job.id,
    name: job.name,
    type: job.type,
    payload: job.payload,
    status: toDisplayStatus(rawStatus),
    rawStatus,
    attempts: job.attempts,
    errorMessage: job.errorMessage ?? null,
    createdAt: job.createdAt,
    startedAt: job.startedAt ?? null,
    completedAt: job.completedAt ?? null,
    version: job.version,
  };
}

/** Prefer the record with the higher server version. Never invent a version. */
export function preferJob(current: JobView | null, incoming: JobView | null): JobView | null {
  if (!incoming) {
    return current;
  }
  if (!current || current.id !== incoming.id) {
    return incoming;
  }
  return incoming.version >= current.version ? incoming : current;
}
