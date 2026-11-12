import { ApplicationConfig, provideBrowserGlobalErrorListeners, provideZonelessChangeDetection } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter, withComponentInputBinding } from '@angular/router';

import { apiErrorInterceptor } from './core/http/api-error.interceptor';
import { appRoutes } from './app.routes';

/**
 * Application-wide providers. Kept here so app bootstrapping is one readable list.
 * Zoneless change detection: components update from signals and template events.
 */
export const appConfig: ApplicationConfig = {
  providers: [
    provideZonelessChangeDetection(),
    provideBrowserGlobalErrorListeners(),
    provideRouter(appRoutes, withComponentInputBinding()),
    provideHttpClient(withInterceptors([apiErrorInterceptor])),
  ],
};
