import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  effect,
  inject,
  input,
  output,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import {
  FormControl,
  FormGroup,
  ReactiveFormsModule,
  type AbstractControl,
  type ValidationErrors,
} from '@angular/forms';
import { merge } from 'rxjs';

import { ApiError } from '../../../../core/http/api-error';
import { type CreateJobRequest } from '../../models/job.model';

type FieldName = 'name' | 'type' | 'payload';

/**
 * Typed reactive form. Limits mirror the OpenAPI contract.
 * Type stays free text: the contract does not publish an enum.
 * Server field errors are written onto the matching control.
 */
@Component({
  selector: 'qb-job-form',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule],
  templateUrl: './job-form.html',
  styleUrl: './job-form.css',
})
export class JobForm {
  readonly submitting = input(false);
  readonly serverError = input<ApiError | null>(null);
  readonly submitted = output<CreateJobRequest>();

  /** Examples only. The contract does not publish a closed list of types. */
  readonly typeExamples = ['echo', 'report'] as const;

  readonly form = new FormGroup({
    name: new FormControl('', {
      nonNullable: true,
      validators: [trimmedRequired, trimmedMax(100)],
    }),
    type: new FormControl('', {
      nonNullable: true,
      validators: [trimmedRequired],
    }),
    payload: new FormControl('', {
      nonNullable: true,
      validators: [trimmedMax(10_000)],
    }),
  });

  private readonly changeDetector = inject(ChangeDetectorRef);

  constructor() {
    merge(this.form.valueChanges, this.form.statusChanges)
      .pipe(takeUntilDestroyed())
      .subscribe(() => this.changeDetector.markForCheck());

    for (const name of ['name', 'type', 'payload'] as const) {
      this.form.controls[name].valueChanges.pipe(takeUntilDestroyed()).subscribe(() => {
        this.clearServerError(this.form.controls[name]);
      });
    }

    effect(() => this.applyServerErrors(this.serverError()));
  }

  useType(value: string): void {
    const control = this.form.controls.type;
    control.setValue(value);
    control.markAsTouched();
  }

  nameLength(): number {
    return this.form.controls.name.value.length;
  }

  payloadLength(): number {
    return this.form.controls.payload.value.length;
  }

  errorFor(field: FieldName): string | null {
    const control = this.form.controls[field];
    if (control.hasError('server')) {
      return String(control.getError('server'));
    }
    if (!control.touched) {
      return null;
    }
    if (control.hasError('required')) {
      return field === 'name' ? 'Enter a name.' : 'Enter a type.';
    }
    if (control.hasError('maxlength')) {
      const max = Number(control.getError('maxlength').requiredLength);
      const label = field === 'name' ? 'Name' : 'Payload';
      return `${label} must be ${max.toLocaleString()} characters or fewer.`;
    }
    return null;
  }

  describedBy(field: FieldName): string {
    const ids = [`job-${field}-hint`];
    if (this.errorFor(field)) {
      ids.push(`job-${field}-error`);
    }
    return ids.join(' ');
  }

  submit(): void {
    if (this.submitting()) {
      return;
    }
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      this.changeDetector.markForCheck();
      return;
    }

    const name = this.form.controls.name.value.trim();
    const type = this.form.controls.type.value.trim();
    const payload = this.form.controls.payload.value.trim();
    this.submitted.emit({
      name,
      type,
      ...(payload ? { payload } : {}),
    });
  }

  private applyServerErrors(error: ApiError | null): void {
    for (const name of ['name', 'type', 'payload'] as const) {
      const control = this.form.controls[name];
      const fieldError = error?.fieldErrors.find((item) => item.field === name);
      const rest = { ...(control.errors ?? {}) };
      delete rest['server'];
      if (fieldError) {
        control.setErrors({ ...rest, server: fieldError.message });
        control.markAsTouched();
      } else if (control.hasError('server')) {
        control.setErrors(Object.keys(rest).length ? rest : null);
      }
    }
    this.changeDetector.markForCheck();
  }

  private clearServerError(control: FormControl<string>): void {
    if (!control.hasError('server')) {
      return;
    }
    const rest = { ...(control.errors ?? {}) };
    delete rest['server'];
    control.setErrors(Object.keys(rest).length ? rest : null);
  }
}

function trimmedRequired(control: AbstractControl<string | null>): ValidationErrors | null {
  return (control.value ?? '').trim().length === 0 ? { required: true } : null;
}

function trimmedMax(max: number) {
  return (control: AbstractControl<string | null>): ValidationErrors | null => {
    const length = (control.value ?? '').trim().length;
    return length > max ? { maxlength: { requiredLength: max, actualLength: length } } : null;
  };
}
