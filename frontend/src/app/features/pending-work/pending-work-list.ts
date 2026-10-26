import { Component, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Icon } from '../../shared/components/icon/icon';
import { PendingWorkItemView } from '../../core/pending-work/pending-work-item';
import { queryParamsOf } from '../../core/pending-work/pending-work.format';
import { PendingWorkItem } from '../../core/pending-work/pending-work.models';

/** Cartões de pendência, cada um levando à tela de ação já filtrada (tela inicial e lista completa). */
@Component({
  selector: 'app-pending-work-list',
  imports: [RouterLink, Icon, PendingWorkItemView],
  template: `
    <ul class="pwl" [attr.aria-label]="label()">
      @for (item of items(); track item.type) {
        <li>
          <a class="pwl__card" [class]="'pwl__card--' + item.severity" [routerLink]="item.route" [queryParams]="params(item)">
            <app-pending-work-item [item]="item" [showHint]="true" />
            <app-icon class="pwl__go" name="arrow-right" />
          </a>
        </li>
      }
    </ul>
  `,
  styles: `
    .pwl {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(min(100%, 340px), 1fr));
      gap: 12px;
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .pwl li {
      display: flex;
      min-width: 0;
    }
    .pwl__card {
      display: flex;
      flex: 1;
      align-items: center;
      gap: 8px;
      min-width: 0;
      min-height: 72px;
      padding: 12px 14px;
      border: 1px solid var(--bs-border-color);
      border-left-width: 4px;
      border-radius: var(--app-radius-lg, 1rem);
      background: var(--bs-body-bg);
      color: var(--bs-body-color);
      text-decoration: none;
      transition:
        border-color var(--app-motion-fast, 0.15s) ease,
        box-shadow var(--app-motion-fast, 0.15s) ease;
    }
    .pwl__card:hover {
      box-shadow: var(--app-elevation-1, var(--bs-box-shadow-sm));
    }
    .pwl__card:hover .pwl__go {
      opacity: 1;
    }
    .pwl__card--OVERDUE {
      border-left-color: var(--bs-danger);
    }
    .pwl__card--DUE_TODAY {
      border-left-color: var(--bs-warning);
    }
    .pwl__card--NORMAL {
      border-left-color: var(--bs-primary-border-subtle);
    }
    .pwl__go {
      flex: none;
      color: var(--bs-secondary-color);
      opacity: 0.6;
    }
  `,
})
export class PendingWorkList {
  readonly items = input.required<readonly PendingWorkItem[]>();
  readonly label = input('Pendências');
  protected readonly params = queryParamsOf;
}
