import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { CellDef } from '../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../shared/components/data-table/data-table';
import { PageHeader } from '../../shared/components/page-header/page-header';
import { StatusChip } from '../../shared/components/status-chip/status-chip';
import { nameMap } from '../../shared/data/name-map';
import { PageRequest } from '../../shared/models/page';
import { AppDatePipe } from '../../shared/pipes/date-time.pipe';
import { CreditorNames } from '../organization/data/organization.lookups';
import { PortalAdminApi, ServiceRequestSummary } from './data/portal-admin.api';

const REASONS: Record<string, string> = {
  TALK_TO_AGENT: 'Falar com atendente',
  DISPUTE: 'Contestação',
  CANNOT_AFFORD: 'Condições não cabem no orçamento',
  OTHER: 'Outro assunto',
};

/**
 * Pedidos do portal que foram (ou não puderam ser) encaminhados à fila humana (`INBOX_VIEW`). A mensagem livre do
 * devedor não vem na listagem (minimização, LGPD): abre sob demanda, pelo detalhe, que registra a consulta no log de
 * acesso.
 */
@Component({
  selector: 'app-portal-requests',
  imports: [RouterLink, DataTable, CellDef, PageHeader, StatusChip, AppDatePipe],
  template: `
    <app-page-header title="Pedidos do portal" subtitle="Devedores que pediram atendimento humano no portal. Os encaminhados estão na caixa de entrada." />
    <app-data-table caption="Pedidos de atendimento do portal" [rows]="rows()" [columns]="columns" [loading]="list.isLoading()"
                    [failed]="list.status() === 'error'" (retry)="list.reload()" [page]="tablePage()" (pageChange)="page.set($event)"
                    emptyIcon="headset" emptyMessage="Nenhum pedido do portal.">
      <ng-template appCell="protocol" [appCellOf]="rows()" let-r><code>{{ r.protocol }}</code><div class="app-muted">{{ r.createdAt | appDate }}</div></ng-template>
      <ng-template appCell="reason" [appCellOf]="rows()" let-r>{{ reasons[r.reason] ?? r.reason }}
        @if (r.hasNote) {
          @if (notes()[r.id]; as note) {
            <div class="app-muted">“{{ note }}”</div>
          } @else {
            <div>
              <button class="btn btn-link btn-sm p-0" type="button" [disabled]="loadingNote() === r.id"
                      (click)="showNote(r)" title="A consulta fica registrada no log de acesso">Ver mensagem do devedor</button>
            </div>
          }
        }</ng-template>
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
  /** Mensagens já abertas nesta visita (não ficam guardadas fora da tela). */
  protected readonly notes = signal<Record<string, string>>({});
  protected readonly loadingNote = signal<string | null>(null);
  protected readonly reasons = REASONS;
  protected readonly page = signal<PageRequest>({ page: 0, size: 20 });
  protected readonly list = rxResource({ params: () => this.page(), stream: ({ params }) => this.api.serviceRequests(null, null, params) });
  protected readonly rows = computed(() => this.list.value()?.content ?? []);
  protected readonly tablePage = computed(() => {
    const p = this.list.value();
    return p ? { index: p.page, size: p.size, total: p.totalElements } : null;
  });
  protected readonly creditors = nameMap(inject(CreditorNames), () => this.rows().map((r) => r.creditorId));

  protected showNote(r: ServiceRequestSummary): void {
    this.loadingNote.set(r.id);
    this.api.serviceRequest(r.id).subscribe({
      next: (detail) => {
        this.loadingNote.set(null);
        this.notes.update((m) => ({ ...m, [r.id]: detail.note ?? '' }));
      },
      // o interceptor de erros já avisa a pessoa
      error: () => this.loadingNote.set(null),
    });
  }

  protected readonly columns: TableColumn[] = [
    { key: 'protocol', header: 'Protocolo' },
    { key: 'reason', header: 'Pedido' },
    { key: 'creditor', header: 'Credor' },
    { key: 'debtor', header: 'Devedor' },
    { key: 'status', header: 'Situação' },
  ];
}
