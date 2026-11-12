import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { type DisplayStatus } from '../../models/job.model';

export type StepState = 'complete' | 'current' | 'upcoming';

export interface LifecycleStep {
  readonly id: string;
  readonly label: string;
  readonly state: StepState;
}

/**
 * Pending → Processing → the real terminal state.
 * UNKNOWN is not drawn as a finished step, so it can never look successful.
 */
export function lifecycleSteps(status: DisplayStatus): readonly LifecycleStep[] | null {
  if (status === 'UNKNOWN') {
    return null;
  }

  const outcome = status === 'SUCCEEDED' ? 'Succeeded' : status === 'FAILED' ? 'Failed' : 'Result';
  const currentIndex = status === 'PENDING' ? 0 : status === 'PROCESSING' ? 1 : 2;
  const labels = ['Pending', 'Processing', outcome];

  return labels.map((label, index) => ({
    id: index === 2 ? 'result' : label.toLowerCase(),
    label,
    state: index === currentIndex ? 'current' : index < currentIndex ? 'complete' : 'upcoming',
  }));
}

@Component({
  selector: 'qb-lifecycle-track',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './lifecycle-track.html',
  styleUrl: './lifecycle-track.css',
})
export class LifecycleTrack {
  readonly status = input.required<DisplayStatus>();
  readonly steps = computed(() => lifecycleSteps(this.status()));
}
