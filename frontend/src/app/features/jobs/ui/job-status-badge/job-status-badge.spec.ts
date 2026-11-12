import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { STATUS_LABELS, type DisplayStatus } from '../../models/job.model';
import { JobStatusBadge } from './job-status-badge';

const STATUSES: readonly DisplayStatus[] = ['PENDING', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'UNKNOWN'];

describe('JobStatusBadge', () => {
  it('renders each status distinctly, and unknown never uses the success treatment', async () => {
    TestBed.configureTestingModule({
      imports: [JobStatusBadge],
      providers: [provideZonelessChangeDetection()],
    });

    const fixture = TestBed.createComponent(JobStatusBadge);

    for (const status of STATUSES) {
      fixture.componentRef.setInput('status', status);
      fixture.detectChanges();
      await fixture.whenStable();

      const element = fixture.nativeElement as HTMLElement;
      expect(element.classList.contains(`badge--${status.toLowerCase()}`)).toBe(true);
      expect(element.textContent).toContain(STATUS_LABELS[status]);
      if (status !== 'SUCCEEDED') {
        expect(element.classList.contains('badge--succeeded')).toBe(false);
      }
    }
  });
});
