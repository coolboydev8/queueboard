import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

@Component({
  selector: 'qb-timestamp',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './timestamp.html',
})
export class Timestamp {
  readonly value = input<string | null>(null);
  readonly formatted = computed(() => formatTimestamp(this.value()));
  readonly iso = computed(() => timestampIso(this.value()));
}

export function formatTimestamp(value: string | null | undefined): string {
  if (!value) {
    return '—';
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat(undefined, {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(date);
}

export function timestampIso(value: string | null | undefined): string | null {
  if (!value) {
    return null;
  }
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}
