import { ChangeDetectionStrategy, Component, input } from '@angular/core';

@Component({
  selector: 'qb-progress-indicator',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './progress-indicator.html',
  styleUrl: './progress-indicator.css',
})
export class ProgressIndicator {
  readonly label = input.required<string>();
}
