import { Component, DestroyRef, computed, inject } from '@angular/core';
import { PendingWorkStore } from '../../core/pending-work/pending-work.store';
import { PendingWorkItem } from '../../core/pending-work/pending-work.models';
import { EmptyState } from '../../shared/components/empty-state/empty-state';
import { Icon } from '../../shared/components/icon/icon';
import { PageHeader } from '../../shared/components/page-header/page-header';
import { Skeleton } from '../../shared/components/skeleton/skeleton';
import { AppDatePipe } from '../../shared/pipes/date-time.pipe';
import { PendingWorkList } from './pending-work-list';

interface AreaGroup {
  area: string;
  items: PendingWorkItem[];
  total: number;
}

/**
 * Lista completa de "Minhas pendências" (`/pendencias`), agrupada por área na ordem de urgência
 * (a área com o item mais urgente primeiro). Cada cartão leva à tela de ação já filtrada.
 */
@Component({
  selector: 'app-pending-work-page',
  imports: [PageHeader, Icon, EmptyState, Skeleton, AppDatePipe, PendingWorkList],
  template: `
    <app-page-header title="Minhas pendências" subtitle="O que depende de você agora, conforme o seu perfil e o seu escopo de credores.">
      <button type="button" class="btn btn-outline-primary" (click)="store.refresh()" [disabled]="store.status() === 'loading'">
        <app-icon name="arrow-clockwise" /> Atualizar
      </button>
    </app-page-header>

    @switch (store.status()) {
      @case ('ready') {
        @if (summary(); as s) {
          <p class="pwpage__meta">
            {{ s.total }} {{ s.total === 1 ? 'item' : 'itens' }}
            @if (s.overdue > 0) {
              · <strong class="text-danger-emphasis">{{ s.overdue }} {{ s.overdue === 1 ? 'vencido' : 'vencidos' }}</strong>
            }
            @if (s.dueToday > 0) {
              · <strong class="text-warning-emphasis">{{ s.dueToday }} {{ s.dueToday === 1 ? 'vence' : 'vencem' }} hoje</strong>
            }
            · atualizado às {{ s.generatedAt | appDate: 'time' }}
          </p>
          @if (s.unavailable.length > 0) {
            <div class="alert alert-warning d-flex align-items-start gap-2" role="status">
              <app-icon name="exclamation-triangle" />
              <span>Não foi possível consultar agora: {{ unavailableText() }}. Tente atualizar em instantes.</span>
            </div>
          }
          @for (group of groups(); track group.area) {
            <section class="pwpage__group" [attr.aria-labelledby]="'pw-' + $index">
              <h2 class="app-section-title" [id]="'pw-' + $index">{{ group.area }} <span class="text-body-secondary">({{ group.total }})</span></h2>
              <app-pending-work-list [items]="group.items" [label]="group.area" />
            </section>
          } @empty {
            <app-empty-state icon="check2-circle" title="Nada pendente" message="Você está em dia. Aprovações, prazos e retornos que dependem de você aparecem aqui." />
          }
        }
      }
      @case ('error') {
        <app-empty-state tone="error" icon="cloud-slash" title="Não foi possível carregar as suas pendências">
          <button type="button" class="btn btn-outline-primary" (click)="store.refresh()">Tentar novamente</button>
        </app-empty-state>
      }
      @default {
        <app-skeleton variant="card" />
      }
    }
  `,
  styles: `
    .pwpage__meta {
      margin: -8px 0 16px;
      color: var(--bs-secondary-color);
    }
    .pwpage__group {
      margin-bottom: 24px;
    }
  `,
})
export class PendingWorkPage {
  protected readonly store = inject(PendingWorkStore);
  protected readonly summary = this.store.summary;

  /** Grupos por área, na ordem em que a área aparece pela primeira vez (o servidor já ordena por urgência). */
  protected readonly groups = computed<AreaGroup[]>(() => {
    const groups = new Map<string, AreaGroup>();
    for (const item of this.summary()?.items ?? []) {
      const group = groups.get(item.area) ?? { area: item.area, items: [], total: 0 };
      group.items.push(item);
      group.total += item.count;
      groups.set(item.area, group);
    }
    return [...groups.values()];
  });
  protected readonly unavailableText = computed(() => (this.summary()?.unavailable ?? []).map((u) => u.area).join(', '));

  constructor() {
    inject(DestroyRef).onDestroy(this.store.connect());
  }
}
