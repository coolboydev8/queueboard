import { HttpClient, HttpErrorResponse, HttpHeaders, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';

import { ApiError } from './api-error';
import { apiErrorInterceptor, toApiError } from './api-error.interceptor';

describe('toApiError', () => {
  it('reads RFC 9457 field errors', () => {
    const apiError = toApiError(
      new HttpErrorResponse({
        status: 400,
        url: '/api/jobs',
        headers: new HttpHeaders({ 'Content-Type': 'application/problem+json' }),
        error: {
          title: 'Invalid input',
          status: 400,
          detail: 'Check the form.',
          errors: [{ field: 'name', message: 'must not be blank' }, { field: 1, message: 'nope' }],
        },
      }),
    );

    expect(apiError).toBeInstanceOf(ApiError);
    expect(apiError.kind).toBe('problem');
    expect(apiError.title).toBe('Invalid input');
    expect(apiError.detail).toBe('Check the form.');
    expect(apiError.fieldErrors).toEqual([{ field: 'name', message: 'must not be blank' }]);
  });

  it('maps status 0 to a backend-unreachable failure', () => {
    const apiError = toApiError(new HttpErrorResponse({ status: 0, url: '/api/jobs', error: null }));

    expect(apiError.kind).toBe('network');
    expect(apiError.title).toBe('Backend unreachable');
    expect(apiError.detail.toLowerCase()).not.toContain('success');
  });

  it('maps a non-problem body to an unexpected failure', () => {
    const apiError = toApiError(
      new HttpErrorResponse({
        status: 500,
        statusText: 'Server Error',
        url: '/api/jobs',
        error: 'boom',
      }),
    );

    expect(apiError.kind).toBe('unexpected');
    expect(apiError.status).toBe(500);
    expect(apiError.title).not.toMatch(/success/i);
  });
});

describe('apiErrorInterceptor', () => {
  it('turns an HTTP problem response into ApiError', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideHttpClient(withInterceptors([apiErrorInterceptor])),
        provideHttpClientTesting(),
      ],
    });

    const http = TestBed.inject(HttpClient);
    const backend = TestBed.inject(HttpTestingController);
    const pending = firstValueFrom(http.get('/api/jobs')).then(
      () => {
        throw new Error('expected the request to fail');
      },
      (error: unknown) => error,
    );

    backend.expectOne('/api/jobs').flush(
      { title: 'Not found', status: 404, detail: 'No job with that id.' },
      {
        status: 404,
        statusText: 'Not Found',
        headers: { 'Content-Type': 'application/problem+json' },
      },
    );

    const error = await pending;
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(404);
    expect((error as ApiError).detail).toBe('No job with that id.');
    backend.verify();
  });
});
