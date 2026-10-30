import { Component, computed, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { Amount } from '../../shared/components/amount/amount';
import { Icon } from '../../shared/components/icon/icon';
import { valueOr } from '../../shared/data/resource-value';
import { TransfersApi } from '../settlement/data/settlement.api';

/**
 * Cartão do painel inicial (gestor, financeiro, auditor): saldo dos demonstrativos aprovados a repassar e a receber,
 * de todos os credores visíveis, com atalho para a posição atualizada de cada credor.
 */
@Component({
  selector: 'app-position-summary-card',
  imports: [Amount, Icon, RouterLink],
  template: `
    <section class="app-card psc" aria-labelledby="psc-title">
      <div class="psc__head">
        <h2 class="app-card__title" id="psc-title">Repasses aos credores</h2>
        <a class="btn btn-link btn-sm d-inline-flex align-items-center gap-1" routerLink="/posicao-credor">Posição do credor <app-icon name="arrow-right" /></a>
      </div>
      @if (balances.isLoading()) {
        <p class="app-muted" role="status">Carregando…</p>
      } @else if (balances.status() === 'error') {
        <p class="app-muted">Não foi possível carregar os saldos.</p>
      } @else {
        <dl class="psc__values">
          <div><dt>A repassar (aprovados)</dt><dd><app-amount [value]="toTransfer()" /></dd></div>
          <div><dt>A receber dos credores</dt><dd><app-amount [value]="toReceive()" /></dd></div>
          <div><dt>Credores com saldo</dt><dd>{{ rows().length }}</dd></div>
        </dl>
        <p class="app-muted psc__hint">O período em aberto de cada credor, estimado agora, está na posição do credor.</p>
      }
    </section>
  `,
  styles: `
    .psc__head { display: flex; justify-content: space-between; align-items: baseline; gap: 8px; }
    .psc__values { display: grid; grid-template-columns: repeat(auto-fill, minmax(min(100%, 170px), 1fr)); gap: 8px 16px; margin: 0; }
    .psc__values dt { font-size: var(--app-text-xs); font-weight: normal; color: var(--bs-secondary-color); }
    .psc__values dd { margin: 0; font-size: 1.25rem; font-weight: 600; }
    .psc__hint { font-size: var(--app-text-xs); margin: 8px 0 0; }
  `,
})
export class PositionSummaryCard {
  private readonly api = inject(TransfersApi);
  protected readonly balances = rxResource({ stream: () => this.api.balances(null) });
  protected readonly rows = valueOr(this.balances, []);
  protected readonly toTransfer = computed(() => this.rows().reduce((s, b) => s + b.toTransfer, 0));
  protected readonly toReceive = computed(() => this.rows().reduce((s, b) => s + b.toReceive, 0));
}
