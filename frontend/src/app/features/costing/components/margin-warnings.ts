import { Component, computed, input } from '@angular/core';
import { ConfigLink } from '../../../shared/components/config-link/config-link';
import { Icon } from '../../../shared/components/icon/icon';
import { COST_SETTINGS_ROUTE } from '../data/costing.labels';
import { MarginWarning } from '../data/costing.models';
import { warningViews } from '../data/margin.rules';

/**
 * Avisos da apuração ("custos incompletos no período") em texto claro, com o atalho para corrigir só para quem tem a
 * permissão da aba de destino. Usado pelo painel e pelo relatório completo da margem; some sem avisos.
 */
@Component({
  selector: 'app-margin-warnings',
  imports: [ConfigLink, Icon],
  template: `
    @if (views().length) {
      <section class="alert alert-warning mw" aria-labelledby="mw-title" data-tour="margin-warnings">
        <h2 class="mw__title" id="mw-title"><app-icon name="exclamation-triangle" /> Custos incompletos no período</h2>
        <ul>
          @for (w of views(); track w.code) {
            <li>
              <span>{{ w.text }}</span>
              @if (w.linkLabel) {
                <app-config-link [route]="settingsRoute" [queryParams]="w.queryParams" [label]="w.linkLabel" [permissions]="w.permissions" />
              }
            </li>
          }
        </ul>
      </section>
    }
  `,
  styles: `
    .mw ul {
      display: grid;
      gap: 6px;
      margin: 0;
      padding-left: 1.25rem;
    }
    .mw li > app-config-link {
      margin-left: 6px;
    }
    .mw__title {
      display: flex;
      align-items: center;
      gap: 6px;
      margin: 0 0 6px;
      font-size: var(--app-text-md);
      font-weight: var(--app-weight-semibold);
    }
  `,
})
export class MarginWarnings {
  readonly warnings = input<readonly MarginWarning[] | null | undefined>();
  protected readonly views = computed(() => warningViews(this.warnings()));
  protected readonly settingsRoute = COST_SETTINGS_ROUTE;
}
