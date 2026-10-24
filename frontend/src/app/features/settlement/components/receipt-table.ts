import { Component, inject, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Amount } from '../../../shared/components/amount/amount';
import { CellDef } from '../../../shared/components/data-table/cell-def';
import { DataTable, TableColumn } from '../../../shared/components/data-table/data-table';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { nameMap } from '../../../shared/data/name-map';
import { PagedQuery } from '../../../shared/data/paged-query';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { CreditorNames } from '../../organization/data/organization.lookups';
import { PAYMENT_TYPE_LABELS, PENDING_REASON_LABELS } from '../data/settlement.labels';
import { ReceiptView } from '../data/settlement.models';
import { ReceiptDialog, ReceiptDialogData } from '../ledger/receipt-dialog';

/** Tabela do razão de comissão (lista geral e recebimentos de um demonstrativo). */
@Component({
  selector: 'app-receipt-table',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule, Amount, AppDatePipe, CellDef, DataTable, StatusChip],
  template: `
    <app-data-table
      caption="Recebimentos e estornos no razão de comissão"
      [rows]="query().rows()"
      [columns]="columns"
      [loading]="query().loading()"
      [failed]="query().failed()"
      [page]="query().tablePage()"
      (pageChange)="query().setPage($event)"
      (retry)="query().reload()"
      emptyIcon="receipt_long"
      [emptyMessage]="emptyMessage()"
      emptyHint="Recebimentos entram no razão quando o pagamento é confirmado e aplicado."
    >
      <ng-template appCell="paidOn" [appCellOf]="query().rows()" let-r>
        {{ r.paidOn | appDate: 'date' }}
        @if (r.competenceDate !== r.paidOn) {
          <div class="app-muted rt-small">competência {{ r.competenceDate | appDate: 'date' }}</div>
        }
      </ng-template>
      <ng-template appCell="title" [appCellOf]="query().rows()" let-r>
        {{ r.kind === 'REVERSAL' ? 'Estorno' : r.agreementId ? 'Parcela de acordo' : 'Título avulso' }}
        <div class="app-muted rt-small">{{ r.titleReference ?? '—' }}@if (showCreditor()) { · {{ creditors.name(r.creditorId) }}}</div>
      </ng-template>
      <ng-template appCell="amount" [appCellOf]="query().rows()" let-r>
        <app-amount [value]="r.amount" [signed]="r.kind === 'REVERSAL'" />
        <div class="app-muted rt-small">{{ r.receivedBy === 'CREDITOR' ? 'pago ao credor' : 'conta da assessoria' }}</div>
      </ng-template>
      <ng-template appCell="rule" [appCellOf]="query().rows()" let-r>
        @if (r.entry; as e) {
          {{ e.calculation.ruleCode }} · v{{ e.ruleVersion }}
          <div class="app-muted rt-small">{{ r.paymentType ? paymentTypes[r.paymentType] : '' }}{{ r.daysPastDue !== null ? ' · ' + r.daysPastDue + ' dias' : '' }}</div>
        } @else {
          <app-status-chip domain="receiptRating" [status]="r.ratingStatus" />
          <div class="app-muted rt-small">{{ pending[r.pendingReason ?? ''] || '' }}</div>
        }
      </ng-template>
      <ng-template appCell="commission" [appCellOf]="query().rows()" let-r>
        @if (r.entry; as e) {
          <app-amount [value]="e.calculation.commission + e.calculation.fixedFee" />
        } @else {
          —
        }
      </ng-template>
      <ng-template appCell="effect" [appCellOf]="query().rows()" let-r>
        @if (r.entry; as e) {
          <app-amount [value]="e.calculation.transferEffect" [signed]="true" />
        } @else {
          —
        }
      </ng-template>
      <ng-template appCell="actions" [appCellOf]="query().rows()" let-r>
        <button mat-icon-button type="button" (click)="open(r)" aria-label="Ver memória de cálculo" matTooltip="Memória de cálculo">
          <mat-icon aria-hidden="true">functions</mat-icon>
        </button>
      </ng-template>
    </app-data-table>
  `,
  styles: `
    .rt-small {
      font-size: var(--app-text-xs);
    }
  `,
})
export class ReceiptTable {
  readonly query = input.required<PagedQuery<ReceiptView>>();
  readonly showCreditor = input(true);
  readonly emptyMessage = input('Nenhum recebimento encontrado.');

  private readonly dialog = inject(MatDialog);
  protected readonly paymentTypes = PAYMENT_TYPE_LABELS;
  protected readonly pending = PENDING_REASON_LABELS;
  protected readonly creditors = nameMap(inject(CreditorNames), () => (this.showCreditor() ? this.query().rows().map((r) => r.creditorId) : []));

  protected readonly columns: TableColumn[] = [
    { key: 'paidOn', header: 'Pago em' },
    { key: 'title', header: 'Origem' },
    { key: 'amount', header: 'Valor', align: 'end' },
    { key: 'rule', header: 'Regra' },
    { key: 'commission', header: 'Comissão + fixo', align: 'end' },
    { key: 'effect', header: 'Efeito no repasse', align: 'end' },
    { key: 'actions', header: 'Ações', actions: true },
  ];

  protected open(r: ReceiptView): void {
    this.dialog.open<ReceiptDialog, ReceiptDialogData>(ReceiptDialog, { data: { receiptId: r.id }, width: '720px', maxWidth: '96vw' });
  }
}
