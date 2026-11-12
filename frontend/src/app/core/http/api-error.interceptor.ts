import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { catchError, throwError } from 'rxjs';

import { ApiError, FieldError } from './api-error';

/**
 * Maps backend RFC 9457 Problem Details into {@link ApiError}.
 * Status 0 is a network failure ("backend unreachable"), never a success.
 */
export const apiErrorInterceptor: HttpInterceptorFn = (req, next) =>
  next(req).pipe(
    catchError((error: unknown) =>
      throwError(() => (error instanceof HttpErrorResponse ? toApiError(error) : error)),
    ),
  );

export function toApiError(error: HttpErrorResponse): ApiError {
  if (error.status === 0) {
    return new ApiError({
      kind: 'network',
      status: 0,
      title: 'Backend unreachable',
      detail: 'The API could not be reached. Nothing was changed.',
      fieldErrors: [],
    });
  }

  const problem = readProblem(error);
  if (problem) {
    return new ApiError({
      kind: 'problem',
      status: typeof problem.status === 'number' ? problem.status : error.status,
      title: problem.title?.trim() || defaultTitle(error.status),
      detail: problem.detail?.trim() || problem.title?.trim() || 'The request failed.',
      fieldErrors: problem.fieldErrors,
    });
  }

  return new ApiError({
    kind: 'unexpected',
    status: error.status,
    title: defaultTitle(error.status),
    detail: 'The server returned an unexpected error.',
    fieldErrors: [],
  });
}

interface ProblemBody {
  readonly title?: string;
  readonly detail?: string;
  readonly status?: number;
  readonly fieldErrors: readonly FieldError[];
}

function readProblem(error: HttpErrorResponse): ProblemBody | null {
  const body = parseBody(error.error);
  if (!body || typeof body !== 'object') {
    return null;
  }

  const record = body as Record<string, unknown>;
  const contentType = error.headers?.get('Content-Type') ?? '';
  const looksLikeProblem =
    contentType.includes('application/problem+json') ||
    (typeof record['title'] === 'string' && typeof record['status'] === 'number');

  if (!looksLikeProblem) {
    return null;
  }

  return {
    title: typeof record['title'] === 'string' ? record['title'] : undefined,
    detail: typeof record['detail'] === 'string' ? record['detail'] : undefined,
    status: typeof record['status'] === 'number' ? record['status'] : undefined,
    fieldErrors: readFieldErrors(record['errors']),
  };
}

function parseBody(error: unknown): unknown {
  if (typeof error !== 'string') {
    return error;
  }
  try {
    return JSON.parse(error) as unknown;
  } catch {
    return null;
  }
}

function readFieldErrors(value: unknown): readonly FieldError[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.flatMap((item) => {
    if (!item || typeof item !== 'object') {
      return [];
    }
    const field = (item as Record<string, unknown>)['field'];
    const message = (item as Record<string, unknown>)['message'];
    if (typeof field !== 'string' || typeof message !== 'string') {
      return [];
    }
    return [{ field, message }];
  });
}

function defaultTitle(status: number): string {
  switch (status) {
    case 400:
      return 'Invalid input';
    case 404:
      return 'Not found';
    case 409:
      return 'Not allowed';
    default:
      return 'Request failed';
  }
}
