import { Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { catchError, of } from 'rxjs';
import { CellDef } from '../../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../../shared/components/data-table/data-table';
import { Icon } from '../../../../shared/components/icon/icon';
import { valueOr } from '../../../../shared/data/resource-value';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { InteractionsApi, TabulationsApi } from '../../../collection/data/collection.api';
import { CHANNEL_ICONS, CHANNEL_LABELS } from '../../../collection/data/collection.labels';
import { InteractionView } from '../../../collection/data/collection.models';

/** Interações mostradas (as mais recentes de todos os casos); o histórico completo de cada caso continua no caso. */
const LIMIT = 50;

const ACTOR_LABELS: Record<InteractionView['actorType'], string> = {
  USER: 'Operador',
  SYSTEM: 'Sistema',
  ASSISTANT: 'Assistente de IA',
  INTEGRATION: 'Integração',
};

/**
 * Histórico de interações do devedor (aba da ficha — revisão de produto 11/2026, T6): as {@link LIMIT} mais recentes
 * de todos os casos do devedor nos credores do escopo, numa chamada (`GET /collection/debtors/{id}/interactions`,
 * `CASE_VIEW`). Cada linha leva ao caso.
 */
@Component({
  selector: 'app-debtor-interactions',
  imports: [DataTable, CellDef, AppDatePipe, Icon, RouterLink],
  template: `
    <app-data-table
      caption="Interações do devedor"
      [rows]="rows()"
      [columns]="columns"
      [loading]="interactions.isLoading()"
      [failed]="interactions.status() === 'error'"
      (retry)="interactions.reload()"
      emptyIcon="chat-left-text"
      emptyMessage="Nenhuma interação registrada nos casos do seu escopo."
    >
      <ng-template appCell="startedAt" [appCellOf]="rows()" let-i>{{ i.startedAt | appDate }}</ng-template>
      <ng-template appCell="channel" [appCellOf]="rows()" let-i>
        <span class="text-nowrap"><app-icon [name]="channelIcons[i.channel]" /> {{ channels[i.channel] }}</span>
        <div class="app-muted di-small">{{ i.direction === 'INBOUND' ? 'Receptivo' : 'Ativo' }}</div>
      </ng-template>
      <ng-template appCell="outcome" [appCellOf]="rows()" let-i>
        {{ outcome(i) }}
        @if (i.effectiveContact) {
          <div class="app-muted di-small">Contato efetivo</div>
        }
      </ng-template>
      <ng-template appCell="case" [appCellOf]="rows()" let-i>
        <a [routerLink]="['/cobranca/casos', i.caseId]">{{ caseLabel()(i.caseId) }}</a>
      </ng-template>
      <ng-template appCell="actor" [appCellOf]="rows()" let-i>{{ actors[i.actorType] }}</ng-template>
      <ng-template appCell="note" [appCellOf]="rows()" let-i><span class="di-note">{{ i.note || '—' }}</span></ng-template>
    </app-data-table>
    @if (truncated()) {
      <p class="app-muted di-small">Mostrando as {{ limit }} interações mais recentes de {{ total() }}; as anteriores ficam no histórico de cada caso.</p>
    }
  `,
  styles: `
    .di-small {
      font-size: var(--app-text-xs);
    }
    .di-note {
      display: -webkit-box;
      -webkit-line-clamp: 3;
      -webkit-box-orient: vertical;
      overflow: hidden;
      overflow-wrap: anywhere;
    }
  `,
})
export class DebtorInteractions {
  readonly debtorId = input.required<string>();
  /** Rótulo do caso (credor · carteira), resolvido pela ficha que já tem os nomes. */
  readonly caseLabel = input<(caseId: string) => string>(() => 'Abrir caso');

  private readonly api = inject(InteractionsApi);
  private readonly tabulationsApi = inject(TabulationsApi);
  protected readonly channels = CHANNEL_LABELS;
  protected readonly channelIcons = CHANNEL_ICONS;
  protected readonly actors = ACTOR_LABELS;
  protected readonly limit = LIMIT;

  protected readonly interactions = rxResource({
    params: () => this.debtorId(),
    stream: ({ params: id }) => this.api.listByDebtor(id, { page: 0, size: LIMIT }),
  });
  private readonly page = valueOr(this.interactions, undefined);
  protected readonly rows = computed<InteractionView[]>(() => this.page()?.content ?? []);
  protected readonly total = computed(() => this.page()?.totalElements ?? 0);
  protected readonly truncated = computed(() => this.total() > this.rows().length);

  private readonly tabulations = rxResource({
    stream: () => this.tabulationsApi.list(false).pipe(catchError(() => of([]))),
  });
  private readonly tabulationNames = computed(
    () => new Map((this.tabulations.hasValue() ? this.tabulations.value() : []).map((t) => [t.id, t.name])),
  );

  protected readonly columns: TableColumn[] = [
    { key: 'startedAt', header: 'Data', mobile: 'meta' },
    { key: 'channel', header: 'Canal', mobile: 'meta' },
    { key: 'outcome', header: 'Resultado', mobile: 'title' },
    { key: 'case', header: 'Caso', mobile: 'meta' },
    { key: 'actor', header: 'Por', mobile: 'hidden' },
    { key: 'note', header: 'Observação' },
  ];

  protected outcome(i: InteractionView): string {
    return (i.tabulationId && this.tabulationNames().get(i.tabulationId)) || i.outcomeCode || '—';
  }
}
