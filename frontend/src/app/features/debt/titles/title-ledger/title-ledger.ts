import { Component, inject, input } from '@angular/core';
import { map } from 'rxjs';
import { Amount } from '../../../../shared/components/amount/amount';
import { CellDef } from '../../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../../shared/components/data-table/data-table';
import { pagedQuery } from '../../../../shared/data/paged-query';
import { AppDatePipe } from '../../../../shared/pipes/date-time.pipe';
import { TitlesApi } from '../../data/debt.api';
import { LEDGER_COMPONENT_LABELS, LEDGER_ENTRY_LABELS, REFERENCE_TYPE_LABELS } from '../../data/debt.labels';

/**
 * Razão do título (lançamentos append-only, do mais recente ao mais antigo). Valores com sinal
 * explícito: positivo aumenta o saldo (abertura, encargo, estorno), negativo reduz.
 */
@Component({
  selector: 'app-title-ledger',
  imports: [DataTable, CellDef, Amount, AppDatePipe],
  template: `
    <app-data-table
      caption="Razão do título"
      [rows]="query.rows()"
      [columns]="columns"
      [loading]="query.loading()"
      [failed]="query.failed()"
      [page]="query.tablePage()"
      (pageChange)="query.setPage($event)"
      (retry)="query.reload()"
      emptyIcon="receipt_long"
      emptyMessage="Nenhum lançamento no razão."
    >
      <ng-template appCell="occurredAt" [appCellOf]="query.rows()" let-e>{{ e.occurredAt | appDate }}</ng-template>
      <ng-template appCell="entryType" [appCellOf]="query.rows()" let-e>{{ entryLabels[e.entryType] ?? e.entryType }}</ng-template>
      <ng-template appCell="component" [appCellOf]="query.rows()" let-e>{{ componentLabels[e.component] ?? e.component }}</ng-template>
      <ng-template appCell="amount" [appCellOf]="query.rows()" let-e><app-amount [value]="e.amount" [signed]="true" /></ng-template>
      <ng-template appCell="origin" [appCellOf]="query.rows()" let-e>
        {{ e.referenceType ? (referenceLabels[e.referenceType] ?? e.referenceType) : '—' }}
      </ng-template>
      <ng-template appCell="reason" [appCellOf]="query.rows()" let-e>{{ e.reason || '—' }}</ng-template>
      <ng-template appCell="recordedAt" [appCellOf]="query.rows()" let-e>
        <span class="app-muted">{{ e.recordedAt | appDate }}</span>
      </ng-template>
    </app-data-table>
  `,
})
export class TitleLedger {
  readonly titleId = input.required<string>();
  private readonly api = inject(TitlesApi);

  protected readonly entryLabels: Record<string, string> = LEDGER_ENTRY_LABELS;
  protected readonly componentLabels: Record<string, string> = LEDGER_COMPONENT_LABELS;
  protected readonly referenceLabels = REFERENCE_TYPE_LABELS;

  protected readonly query = pagedQuery(this.titleId, (id, page) => this.api.ledger(id, page).pipe(map((l) => l.entries)));

  protected readonly columns: TableColumn[] = [
    { key: 'occurredAt', header: 'Data' },
    { key: 'entryType', header: 'Lançamento' },
    { key: 'component', header: 'Componente' },
    { key: 'amount', header: 'Valor', align: 'end' },
    { key: 'origin', header: 'Origem' },
    { key: 'reason', header: 'Motivo' },
    { key: 'recordedAt', header: 'Registrado em' },
  ];
}
