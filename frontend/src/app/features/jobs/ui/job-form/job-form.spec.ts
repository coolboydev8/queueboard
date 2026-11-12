import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';

import { ApiError } from '../../../../core/http/api-error';
import { type CreateJobRequest } from '../../models/job.model';
import { JobForm } from './job-form';

describe('JobForm', () => {
  async function setup() {
    TestBed.configureTestingModule({
      imports: [JobForm],
      providers: [provideZonelessChangeDetection()],
    });
    const fixture = TestBed.createComponent(JobForm);
    fixture.detectChanges();
    await fixture.whenStable();
    const emitted: CreateJobRequest[] = [];
    fixture.componentInstance.submitted.subscribe((value) => emitted.push(value));
    return { fixture, emitted };
  }

  it('does not submit invalid input and shows a field message', async () => {
    const { fixture, emitted } = await setup();

    (fixture.nativeElement as HTMLElement).querySelector('button[type="submit"]')?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    fixture.detectChanges();
    await fixture.whenStable();

    expect(emitted).toEqual([]);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Enter a name.');
  });

  it('writes a 400 field error onto that control', async () => {
    const { fixture } = await setup();

    fixture.componentRef.setInput(
      'serverError',
      new ApiError({
        kind: 'problem',
        status: 400,
        title: 'Invalid input',
        detail: 'Check the form.',
        fieldErrors: [{ field: 'name', message: 'Name is already used' }],
      }),
    );
    TestBed.flushEffects();
    fixture.detectChanges();
    await fixture.whenStable();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('Name is already used');
    expect(fixture.componentInstance.form.controls.name.getError('server')).toBe('Name is already used');
  });
});
