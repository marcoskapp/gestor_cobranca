import { Component, DestroyRef, computed, inject, input } from '@angular/core';
import { RouterLink } from '@angular/router';
import { PendingWorkStore } from '../../core/pending-work/pending-work.store';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Icon } from '../../shared/components/icon/icon';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import { PendingWorkList } from './pending-work-list';

/**
 * "Suas pendências" no topo da tela inicial: as primeiras pendências do usuário (vencidas primeiro),
 * cada uma com link para a tela de ação, estado vazio positivo e aviso das áreas sem resposta.
 */
@Component({
  selector: 'app-pending-work-panel',
  imports: [RouterLink, Icon, EmptyState, Skeleton, PendingWorkList],
  template: `
    <section class="pwp" aria-labelledby="pwp-title" data-tour="pending-work-panel">
      <div class="pwp__head">
        <h2 class="app-section-title" id="pwp-title">
          Suas pendências
          @if (total() > 0) {
            <span class="badge rounded-pill bg-primary-subtle text-primary-emphasis border border-primary-subtle ms-1">{{ total() }}</span>
          }
        </h2>
        @if (store.status() === 'ready' && items().length > 0) {
          <a class="btn btn-link" routerLink="/pendencias">
            Ver todas
            <app-icon name="arrow-right" />
          </a>
        }
      </div>
      @switch (store.status()) {
        @case ('ready') {
          @if (visible().length > 0) {
            <app-pending-work-list [items]="visible()" label="Suas pendências" />
            @if (hidden() > 0) {
              <p class="pwp__more"><a routerLink="/pendencias">Mais {{ hidden() }} na lista completa</a></p>
            }
          } @else {
            <div class="pwp__empty">
              <app-empty-state
                icon="check2-circle"
                title="Nada pendente"
                message="Você está em dia. Aprovações, prazos e retornos que dependem de você aparecem aqui."
                [compact]="true"
              />
            </div>
          }
          @if (unavailable()) {
            <p class="pwp__warn" role="status">
              <app-icon name="exclamation-triangle" />
              Não foi possível consultar agora: {{ unavailable() }}. As demais pendências estão atualizadas.
            </p>
          }
        }
        @case ('error') {
          <app-empty-state tone="error" icon="cloud-slash" title="Não foi possível carregar as suas pendências" [compact]="true">
            <button type="button" class="btn btn-outline-primary" (click)="store.refresh()">Tentar novamente</button>
          </app-empty-state>
        }
        @default {
          <app-skeleton variant="lines" [rows]="2" />
        }
      }
    </section>
  `,
  styles: `
    .pwp {
      margin-bottom: 24px;
    }
    .pwp__head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 8px;
    }
    .pwp__empty {
      border: 1px dashed var(--bs-border-color);
      border-radius: var(--app-radius-lg, 1rem);
      background: var(--bs-body-bg);
    }
    .pwp__more {
      margin: 8px 0 0;
    }
    .pwp__warn {
      display: flex;
      align-items: center;
      gap: 8px;
      margin: 8px 0 0;
      color: var(--bs-warning-text-emphasis);
      font-size: var(--app-text-sm, 0.8125rem);
    }
  `,
})
export class PendingWorkPanel {
  protected readonly store = inject(PendingWorkStore);
  /** Cartões na tela inicial; o restante fica na lista completa. */
  readonly limit = input(6);

  protected readonly items = computed(() => this.store.summary()?.items ?? []);
  protected readonly total = this.store.total;
  protected readonly visible = computed(() => this.items().slice(0, this.limit()));
  protected readonly hidden = computed(() => Math.max(0, this.items().length - this.limit()));
  protected readonly unavailable = computed(() => (this.store.summary()?.unavailable ?? []).map((u) => u.area).join(', '));

  constructor() {
    inject(DestroyRef).onDestroy(this.store.connect());
  }
}
