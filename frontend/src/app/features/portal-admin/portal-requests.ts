import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { CellDef } from '../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../shared/components/data-table/data-table';
import { PageHeader } from '../../shared/components/page-header/page-header';
import { StatusChip } from '../../shared/components/status-chip/status-chip';
import { nameMap } from '../../shared/data/name-map';
import { PageRequest } from '../../shared/models/page';
import { AppDatePipe } from '../../shared/pipes/date-time.pipe';
import { CreditorNames } from '../organization/data/organization.lookups';
import { PortalAdminApi } from './data/portal-admin.api';

const REASONS: Record<string, string> = {
  TALK_TO_AGENT: 'Falar com atendente',
  DISPUTE: 'Contestação',
  CANNOT_AFFORD: 'Condições não cabem no orçamento',
  OTHER: 'Outro assunto',
};

/** Pedidos do portal que foram (ou não puderam ser) encaminhados à fila humana (`INBOX_VIEW`). */
@Component({
  selector: 'app-portal-requests',
  imports: [RouterLink, MatIconModule, DataTable, CellDef, PageHeader, StatusChip, AppDatePipe],
  template: `
    <app-page-header title="Pedidos do portal" subtitle="Devedores que pediram atendimento humano no portal. Os encaminhados estão na caixa de entrada." />
    <app-data-table caption="Pedidos de atendimento do portal" [rows]="rows()" [columns]="columns" [loading]="list.isLoading()"
                    [failed]="list.status() === 'error'" (retry)="list.reload()" [page]="tablePage()" (pageChange)="page.set($event)"
                    emptyIcon="support_agent" emptyMessage="Nenhum pedido do portal.">
      <ng-template appCell="protocol" [appCellOf]="rows()" let-r><code>{{ r.protocol }}</code><div class="app-muted">{{ r.createdAt | appDate }}</div></ng-template>
      <ng-template appCell="reason" [appCellOf]="rows()" let-r>{{ reasons[r.reason] ?? r.reason }}
        @if (r.note) {<div class="app-muted">“{{ r.note }}”</div>}</ng-template>
      <ng-template appCell="creditor" [appCellOf]="rows()" let-r>{{ creditors.name(r.creditorId) }}</ng-template>
      <ng-template appCell="debtor" [appCellOf]="rows()" let-r><a [routerLink]="['/devedores', r.debtorId]">Ver devedor</a></ng-template>
      <ng-template appCell="status" [appCellOf]="rows()" let-r><app-status-chip domain="portalRequest" [status]="r.status" />
        @if (r.conversationId) {<div><a routerLink="/conversas" [queryParams]="{ conversa: r.conversationId }">Abrir conversa</a></div>}
        @if (r.failureCode) {<div class="app-muted">{{ r.failureCode }}</div>}</ng-template>
    </app-data-table>
  `,
})
export class PortalRequests {
  private readonly api = inject(PortalAdminApi);
  protected readonly reasons = REASONS;
  protected readonly page = signal<PageRequest>({ page: 0, size: 20 });
  protected readonly list = rxResource({ params: () => this.page(), stream: ({ params }) => this.api.serviceRequests(null, null, params) });
  protected readonly rows = computed(() => this.list.value()?.content ?? []);
  protected readonly tablePage = computed(() => {
    const p = this.list.value();
    return p ? { index: p.page, size: p.size, total: p.totalElements } : null;
  });
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.rows().map((r) => r.creditorId));
  protected readonly columns: TableColumn[] = [
    { key: 'protocol', header: 'Protocolo' },
    { key: 'reason', header: 'Pedido' },
    { key: 'creditor', header: 'Credor' },
    { key: 'debtor', header: 'Devedor' },
    { key: 'status', header: 'Situação' },
  ];
}
