import { ChangeDetectionStrategy, Component, input } from '@angular/core';

@Component({
  selector: 'qb-empty-state',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './empty-state.html',
  styleUrl: './empty-state.css',
})
export class EmptyState {
  readonly title = input.required<string>();
  readonly message = input('');
}
