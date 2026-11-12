import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';

import { EmptyState } from '../../shared/ui/empty-state/empty-state';

@Component({
  selector: 'qb-not-found',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [RouterLink, EmptyState],
  templateUrl: './not-found.html',
})
export class NotFoundPage {}
