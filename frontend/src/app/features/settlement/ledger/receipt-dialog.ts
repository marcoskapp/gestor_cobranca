import { Component, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { Amount } from '../../../shared/components/amount/amount';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { CalculationMemory } from '../components/calculation-memory';
import { CommissionLedgerApi } from '../data/settlement.api';
import { PAYMENT_TYPE_LABELS, PENDING_REASON_LABELS, RECEIVED_BY_LABELS } from '../data/settlement.labels';

export interface ReceiptDialogData {
  receiptId: string;
}

/** Recebimento do razão com a memória de cálculo do lançamento e os estornos que o compensaram. */
@Component({
  selector: 'app-receipt-dialog',
  imports: [MatDialogModule, MatButtonModule, MatIconModule, Amount, AppDatePipe, CalculationMemory, EmptyState, Skeleton, StatusChip],
  template: `
    <h2 mat-dialog-title>Recebimento no razão</h2>
    <mat-dialog-content>
      @if (resource.isLoading()) {
        <app-skeleton variant="lines" [rows]="6" />
      } @else if (resource.status() === 'error') {
        <app-empty-state icon="cloud_off" tone="error" title="Não foi possível carregar o recebimento." [compact]="true" />
      } @else if (detail(); as d) {
        @let r = d.receipt;
        <dl class="app-definition-list rd-dl">
          <dt>Tipo</dt>
          <dd>{{ r.kind === 'PAYMENT' ? 'Recebimento' : 'Estorno' }} · <app-status-chip domain="receiptRating" [status]="r.ratingStatus" /></dd>
          <dt>Valor</dt>
          <dd><app-amount [value]="r.amount" [signed]="r.kind === 'REVERSAL'" /> · honorários <app-amount [value]="r.honorarium" /></dd>
          <dt>Pago em</dt>
          <dd>{{ r.paidOn | appDate: 'date' }} · competência {{ r.competenceDate | appDate: 'date' }}</dd>
          <dt>Título no credor</dt>
          <dd>{{ r.titleReference ?? '—' }}</dd>
          <dt>Perfil</dt>
          <dd>{{ r.paymentType ? paymentTypes[r.paymentType] : '—' }} · {{ received[r.receivedBy] }} · {{ r.daysPastDue ?? '—' }} dias de atraso{{ r.renegotiation ? ' · renegociação' : '' }}</dd>
          @if (r.pendingReason) {
            <dt>Pendência</dt>
            <dd>{{ pending[r.pendingReason] || r.pendingReason }}</dd>
          }
          @if (r.reversalReason) {
            <dt>Motivo do estorno</dt>
            <dd>{{ r.reversalReason }}</dd>
          }
          <dt>Prestação de contas</dt>
          <dd>{{ r.statementId ? 'Incluído em demonstrativo' : 'Ainda não prestado' }}</dd>
        </dl>
        @if (r.entry; as e) {
          <h3 class="app-section-title">Lançamento · regra v{{ e.ruleVersion }}</h3>
          <app-calculation-memory [calculation]="e.calculation" />
        } @else {
          <p class="app-alert app-alert--warning" role="note"><mat-icon aria-hidden="true">hourglass_top</mat-icon><span>Aguardando regra de comissão: será apurado quando a regra vigente na data do pagamento for publicada.</span></p>
        }
        @if (d.reversals.length) {
          <h3 class="app-section-title">Estornos deste recebimento</h3>
          <ul class="rd-list">
            @for (x of d.reversals; track x.id) {
              <li>{{ x.competenceDate | appDate: 'date' }} · <app-amount [value]="x.amount" [signed]="true" /> · efeito <app-amount [value]="x.entry?.calculation?.transferEffect ?? 0" [signed]="true" /></li>
            }
          </ul>
        }
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Fechar</button>
    </mat-dialog-actions>
  `,
  styles: `
    .rd-dl {
      margin-bottom: 12px;
    }
    .rd-list {
      margin: 0;
      padding-left: 20px;
    }
  `,
})
export class ReceiptDialog {
  protected readonly data = inject<ReceiptDialogData>(MAT_DIALOG_DATA);
  private readonly api = inject(CommissionLedgerApi);
  protected readonly paymentTypes = PAYMENT_TYPE_LABELS;
  protected readonly received = RECEIVED_BY_LABELS;
  protected readonly pending = PENDING_REASON_LABELS;
  protected readonly resource = rxResource({ stream: () => this.api.get(this.data.receiptId) });
  protected readonly detail = valueOr(this.resource, undefined);
}
