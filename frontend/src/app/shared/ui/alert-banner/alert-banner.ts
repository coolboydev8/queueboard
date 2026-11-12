import { ChangeDetectionStrategy, Component, input } from '@angular/core';

@Component({
  selector: 'qb-alert-banner',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './alert-banner.html',
  styleUrl: './alert-banner.css',
})
export class AlertBanner {
  readonly tone = input<'error' | 'warning' | 'info'>('error');
  readonly heading = input.required<string>();
  readonly message = input('');
}
