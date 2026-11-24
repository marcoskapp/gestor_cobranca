import { Component, computed, inject } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { map } from 'rxjs';
import { Amount } from '../../shared/components/amount/amount';
import { CellDef } from '../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../shared/components/data-table/data-table';
import { Icon } from '../../shared/components/icon/icon';
import { StatusChip } from '../../shared/components/status-chip/status-chip';
import { AppDatePipe } from '../../shared/pipes/date-time.pipe';
import { CreditorDisputesApi } from '../data/creditor-disputes.api';
import {
  CREDITOR_DISPUTE_OUTCOME_LABELS,
  CREDITOR_DISPUTE_TYPE_LABELS,
  CreditorDisputeItem,
  CreditorDisputeSituation,
} from '../data/creditor.models';

/** Texto curto do que acontece agora na contestação, para a lista e a ficha. */
export function disputeSituationText(d: CreditorDisputeItem): string {
  if (d.situation === 'CLOSED') {
    return d.outcome ? CREDITOR_DISPUTE_OUTCOME_LABELS[d.outcome] : 'Encerrada pela assessoria';
  }
  if (d.situation === 'ANSWERED') {
    return 'A assessoria analisa a sua resposta';
  }
  return d.overdue ? 'Prazo pedido pela assessoria vencido' : 'A assessoria aguarda a sua resposta';
}

type Tab = CreditorDisputeSituation | 'ALL';

const TABS: readonly { value: Tab; label: string; empty: string; hint: string }[] = [
  { value: 'AWAITING_YOU', label: 'Aguardando você', empty: 'Nada depende de você agora.',
    hint: 'Quando a assessoria pedir a sua posição sobre uma contestação de devedor, ela aparece aqui para você responder.' },
  { value: 'ANSWERED', label: 'Em análise', empty: 'Nenhuma resposta sua em análise.',
    hint: 'Depois que você responde, a contestação fica aqui até a assessoria decidir.' },
  { value: 'CLOSED', label: 'Encerradas', empty: 'Nenhuma contestação encerrada.',
    hint: 'As contestações decididas pela assessoria aparecem aqui com o resultado.' },
  { value: 'ALL', label: 'Todas', empty: 'Nenhuma contestação com pedido da assessoria.',
    hint: 'Quando a assessoria pedir a sua posição sobre uma contestação de devedor, ela aparece aqui.' },
];

/**
 * Contestações no portal do credor: as que a assessoria pediu a sua posição, com abas por situação (na URL, em
 * `?situacao=`; padrão "Aguardando você"). Cada linha leva à ficha, onde a pessoa lê o pedido, baixa os documentos e
 * responde.
 */
@Component({
  selector: 'app-creditor-disputes',
  imports: [RouterLink, Amount, AppDatePipe, CellDef, DataTable, Icon, StatusChip],
  template: `
    <h1 class="h4">Contestações</h1>
    <p class="app-muted">Contestações de devedores em que a assessoria pediu a sua posição. Quem decide é a assessoria.</p>

    <section class="app-card" aria-labelledby="cd-lista">
      <h2 class="app-card__title" id="cd-lista">Pedidos da assessoria</h2>
      <nav class="nav nav-tabs mb-3 app-scroll-x" aria-label="Filtrar por situação">
        @for (t of tabs; track t.value) {
          <a class="nav-link" [routerLink]="[]" [queryParams]="{ situacao: t.value === 'AWAITING_YOU' ? null : t.value }"
             [class.active]="tab() === t.value" [attr.aria-current]="tab() === t.value ? 'page' : null">
            {{ t.label }}@if (list.value()) { ({{ count(t.value) }}) }
          </a>
        }
      </nav>
      <app-data-table
        caption="Contestações com pedido da assessoria ao credor"
        [rows]="rows()"
        [columns]="columns"
        [loading]="list.isLoading()"
        [failed]="!!list.error()"
        (retry)="list.reload()"
        emptyIcon="chat-square-text"
        [emptyMessage]="current().empty"
        [emptyHint]="current().hint"
      >
        <ng-template appCell="protocol" [appCellOf]="rows()" let-d>
          <a [routerLink]="[d.id]">Protocolo {{ d.protocol }}</a>
          <div class="app-muted cd-small">{{ typeLabel(d.type) }}</div>
        </ng-template>
        <ng-template appCell="situation" [appCellOf]="rows()" let-d>
          <app-status-chip domain="creditorDispute" [status]="d.situation" />
        </ng-template>
        <ng-template appCell="due" [appCellOf]="rows()" let-d>
          @switch (d.situation) {
            @case ('AWAITING_YOU') {
              @if (d.requestedDueDate) {
                <span [class.cd-late]="d.overdue">até {{ d.requestedDueDate | appDate: 'date' }}</span>
                @if (d.overdue) {
                  <div class="cd-late cd-small"><app-icon name="exclamation-triangle" /> Prazo vencido</div>
                }
              }
            }
            @case ('ANSWERED') {
              <span class="app-muted">Respondida em {{ d.lastResponseAt | appDate: 'date' }}</span>
            }
            @default {
              <span>{{ situationText(d) }}</span>
            }
          }
        </ng-template>
        <ng-template appCell="amount" [appCellOf]="rows()" let-d><app-amount [value]="d.disputedAmount" /></ng-template>
        <ng-template appCell="openedAt" [appCellOf]="rows()" let-d>{{ d.openedAt | appDate: 'date' }}</ng-template>
      </app-data-table>
    </section>
  `,
  styles: `
    .cd-small {
      font-size: var(--app-text-xs);
    }
    .cd-late {
      font-weight: var(--app-weight-semibold);
      color: var(--app-danger-fg);
    }
  `,
})
export class CreditorDisputes {
  private readonly api = inject(CreditorDisputesApi);
  private readonly route = inject(ActivatedRoute);
  protected readonly list = rxResource({ stream: () => this.api.list() });
  protected readonly tabs = TABS;
  protected readonly tab = toSignal(this.route.queryParamMap.pipe(map((q) => {
    const value = q.get('situacao');
    return (TABS.some((t) => t.value === value) ? value : 'AWAITING_YOU') as Tab;
  })), { initialValue: 'AWAITING_YOU' as Tab });
  protected readonly current = computed(() => TABS.find((t) => t.value === this.tab())!);
  protected readonly rows = computed(() => {
    const all = this.list.value() ?? [];
    const tab = this.tab();
    return tab === 'ALL' ? all : all.filter((d) => d.situation === tab);
  });
  protected readonly count = (tab: Tab) => {
    const all = this.list.value() ?? [];
    return tab === 'ALL' ? all.length : all.filter((d) => d.situation === tab).length;
  };
  protected readonly typeLabel = (type: string) => CREDITOR_DISPUTE_TYPE_LABELS[type] ?? 'Contestação';
  protected readonly situationText = disputeSituationText;

  protected readonly columns: TableColumn[] = [
    { key: 'protocol', header: 'Contestação', mobile: 'title' },
    { key: 'situation', header: 'Situação', mobile: 'badge' },
    { key: 'due', header: 'Prazo ou resultado' },
    { key: 'amount', header: 'Valor contestado', align: 'end' },
    { key: 'openedAt', header: 'Aberta em' },
  ];
}
