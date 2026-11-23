import { Component, computed, input, output } from '@angular/core';
import { Permission } from '../../../core/auth/permission';
import { ConfigLink } from '../../../shared/components/config-link/config-link';
import { InfoHint } from '../../../shared/components/info-hint/info-hint';
import { COST_SETTINGS_ROUTE } from '../data/costing.labels';
import { MarginBasis, MarginReport } from '../data/costing.models';
import { basisBadge } from '../data/margin.rules';

/**
 * Seletor da base dos custos (estimada ou conciliada com a fatura, margem-por-carteira.md §13) com o selo, a
 * explicação e o atalho para os custos operacionais. Usado pelo painel e pelo relatório completo da margem.
 */
@Component({
  selector: 'app-margin-basis-bar',
  imports: [ConfigLink, InfoHint],
  template: `
    <div class="mb__basis" data-tour="margin-basis">
      <div class="btn-group btn-group-sm" role="radiogroup" aria-label="Base dos custos">
        @for (option of options; track option.value) {
          <input type="radio" class="btn-check" name="mp-basis" [id]="'mp-basis-' + option.value" [checked]="basis() === option.value"
                 (change)="changed.emit(option.value)" />
          <label class="btn btn-outline-secondary" [for]="'mp-basis-' + option.value">{{ option.label }}</label>
        }
      </div>
      <span [class]="'badge rounded-pill text-bg-' + badge().tone">{{ badge().label }}</span>
      <span>{{ badge().text }}</span>
      @if (reconciled()) {
        <app-info-hint indicator="analytics.margin.reconciled" label="Conciliado com a fatura" />
      } @else {
        <app-info-hint indicator="analytics.margin.estimated" label="Valores estimados" />
      }
      <app-config-link [route]="settingsRoute" label="Custos operacionais" [permissions]="settingsPermissions" />
      @if (invoicesAvailable()) {
        <span class="mb__note">Há faturas de canal informadas no período: escolha "Conciliado com a fatura" para ver os custos com elas.</span>
      }
    </div>
  `,
  styles: `
    .mb__basis {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 4px 8px;
      margin: 0 0 var(--app-gap);
      font-size: var(--app-text-sm);
      color: var(--bs-secondary-color);
    }
    .mb__note {
      flex-basis: 100%;
    }
  `,
})
export class MarginBasisBar {
  readonly basis = input.required<MarginBasis>();
  /** Relatório carregado (a conciliação diz se há faturas no período); indefinido enquanto carrega. */
  readonly report = input<MarginReport | undefined>();
  readonly changed = output<MarginBasis>();

  protected readonly options: readonly { value: MarginBasis; label: string }[] = [
    { value: 'ESTIMATED', label: 'Estimado' },
    { value: 'RECONCILED', label: 'Conciliado com a fatura' },
  ];
  protected readonly settingsRoute = COST_SETTINGS_ROUTE;
  protected readonly settingsPermissions = [Permission.COST_MANAGE, Permission.COST_RATE_MANAGE];
  protected readonly reconciled = computed(() => this.basis() === 'RECONCILED');
  protected readonly badge = computed(() => basisBadge(this.basis(), this.report()?.reconciliation));
  /** Na base estimada, avisa que há faturas para ver a base conciliada. */
  protected readonly invoicesAvailable = computed(() => {
    const status = this.report()?.reconciliation?.status;
    return !this.reconciled() && !!status && status !== 'ESTIMATED';
  });
}
