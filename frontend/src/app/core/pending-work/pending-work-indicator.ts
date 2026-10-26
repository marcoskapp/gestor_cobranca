import { Component, DestroyRef, computed, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { NgbDropdownModule, NgbTooltip } from '@ng-bootstrap/ng-bootstrap';
import { Icon } from '../../shared/components/icon/icon';
import { PendingWorkItemView } from './pending-work-item';
import { badgeText, indicatorLabel, queryParamsOf } from './pending-work.format';
import { PendingWorkStore } from './pending-work.store';

/** Itens no resumo da barra superior; a lista completa fica em `/pendencias`. */
export const INDICATOR_MAX_ITEMS = 6;

/**
 * Indicador de pendências na barra superior: selo com o total (vermelho com algum vencido) e menu
 * com as primeiras pendências, cada uma levando à tela de ação já filtrada, e o atalho para a lista
 * completa. O rótulo acessível traz total, vencidos e de hoje; mudanças são anunciadas pela store.
 */
@Component({
  selector: 'app-pending-work-indicator',
  imports: [NgbDropdownModule, NgbTooltip, RouterLink, Icon, PendingWorkItemView],
  template: `
    <div ngbDropdown placement="bottom-end" class="d-inline-flex" (openChange)="$event && store.refreshIfStale()">
      <button
        type="button"
        ngbDropdownToggle
        class="btn app-topbar__btn position-relative"
        data-tour="pending-work"
        [attr.aria-label]="label()"
        ngbTooltip="Suas pendências"
      >
        <app-icon name="inbox" />
        @if (total() > 0) {
          <span class="pwind__badge badge rounded-pill" [class.pwind__badge--overdue]="overdue() > 0" aria-hidden="true">{{
            badge()
          }}</span>
        }
      </button>
      <div ngbDropdownMenu class="shadow app-menu pwind__menu" role="menu" aria-label="Suas pendências">
        <h6 class="dropdown-header" role="presentation">Suas pendências</h6>
        @switch (store.status()) {
          @case ('error') {
            <p class="pwind__note" role="presentation">Não foi possível carregar as pendências agora.</p>
            <button ngbDropdownItem type="button" role="menuitem" (click)="store.refresh()">
              <app-icon name="arrow-clockwise" /> Tentar novamente
            </button>
          }
          @case ('ready') {
            @for (item of items(); track item.type) {
              <a ngbDropdownItem role="menuitem" class="pwind__item" [routerLink]="item.route" [queryParams]="params(item)">
                <app-pending-work-item [item]="item" />
              </a>
            } @empty {
              <p class="pwind__note pwind__note--ok" role="presentation">
                <app-icon name="check2-circle" /> Nada pendente. Você está em dia.
              </p>
            }
            @if (unavailable()) {
              <p class="pwind__note" role="presentation">
                <app-icon name="exclamation-triangle" /> Sem resposta de: {{ unavailable() }}.
              </p>
            }
          }
          @default {
            <p class="pwind__note" role="presentation">Carregando…</p>
          }
        }
        <div class="dropdown-divider" role="separator"></div>
        <a ngbDropdownItem role="menuitem" routerLink="/pendencias">
          <app-icon name="list-check" /> Ver todas as pendências
          @if (hidden() > 0) {
            <span class="ms-auto text-body-secondary">+{{ hidden() }}</span>
          }
        </a>
      </div>
    </div>
  `,
  styles: `
    .pwind__badge {
      position: absolute;
      top: 2px;
      right: 0;
      min-width: 1.25rem;
      padding: 0.15rem 0.35rem;
      font-size: 0.6875rem;
      font-weight: var(--bs-font-weight-semibold, 500);
      font-variant-numeric: tabular-nums;
      line-height: 1;
      /* invertido sobre a barra superior (contraste do texto 13,4:1 claro / 14,6:1 escuro) */
      background: var(--app-topbar-fg);
      color: var(--app-topbar-bg);
    }
    .pwind__badge--overdue {
      background: var(--bs-danger);
      color: #fff;
    }
    .pwind__menu {
      width: min(400px, calc(100vw - 32px));
      max-height: min(70vh, 560px);
      overflow-y: auto;
    }
    .pwind__item {
      white-space: normal;
      padding-block: 8px;
    }
    .pwind__note {
      display: flex;
      align-items: center;
      gap: 8px;
      margin: 0;
      padding: 6px 16px;
      color: var(--bs-secondary-color);
      font-size: var(--app-text-sm, 0.8125rem);
    }
    .pwind__note--ok {
      color: var(--bs-success-text-emphasis);
    }
  `,
})
export class PendingWorkIndicator {
  protected readonly store = inject(PendingWorkStore);

  protected readonly total = this.store.total;
  protected readonly overdue = this.store.overdue;
  protected readonly badge = computed(() => badgeText(this.total()));
  protected readonly label = computed(() => indicatorLabel(this.store.summary()));
  protected readonly items = computed(() => (this.store.summary()?.items ?? []).slice(0, INDICATOR_MAX_ITEMS));
  protected readonly hidden = computed(() => Math.max(0, (this.store.summary()?.items.length ?? 0) - INDICATOR_MAX_ITEMS));
  protected readonly unavailable = computed(() => (this.store.summary()?.unavailable ?? []).map((u) => u.area).join(', '));
  protected readonly params = queryParamsOf;

  constructor() {
    inject(DestroyRef).onDestroy(this.store.connect());
  }
}
