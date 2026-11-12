/**
 * Typed failure the UI can render. One shape for RFC 9457 problems, a down API,
 * and anything else — never a success.
 */

export type ApiErrorKind = 'problem' | 'network' | 'unexpected';

export interface FieldError {
  readonly field: string;
  readonly message: string;
}

export interface ApiErrorInit {
  readonly kind: ApiErrorKind;
  readonly status: number;
  readonly title: string;
  readonly detail: string;
  readonly fieldErrors: readonly FieldError[];
}

export class ApiError extends Error {
  readonly kind: ApiErrorKind;
  readonly status: number;
  readonly title: string;
  readonly detail: string;
  readonly fieldErrors: readonly FieldError[];

  constructor(init: ApiErrorInit) {
    super(init.detail || init.title);
    this.name = 'ApiError';
    this.kind = init.kind;
    this.status = init.status;
    this.title = init.title;
    this.detail = init.detail;
    this.fieldErrors = init.fieldErrors;
  }
}

export function asApiError(error: unknown): ApiError {
  if (error instanceof ApiError) {
    return error;
  }
  return new ApiError({
    kind: 'unexpected',
    status: 0,
    title: 'Unexpected error',
    detail: 'Something went wrong before the server could answer.',
    fieldErrors: [],
  });
}
