import { Component, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Amount } from '../../../shared/components/amount/amount';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { StatusChip } from '../../../shared/components/status-chip/status-chip';
import { valueOr } from '../../../shared/data/resource-value';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { CalculationMemory } from '../components/calculation-memory';
import { CommissionLedgerApi } from '../data/settlement.api';
import { PAYMENT_TYPE_LABELS, PENDING_REASON_LABELS, RECEIVED_BY_LABELS } from '../data/settlement.labels';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';

export interface ReceiptDialogData {
  receiptId: string;
}

/** Recebimento do razão com a memória de cálculo do lançamento e os estornos que o compensaram. */
@Component({
  selector: 'app-receipt-dialog',
  imports: [Icon, ...DIALOG_DIRECTIVES, Amount, AppDatePipe, CalculationMemory, EmptyState, Skeleton, StatusChip],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Recebimento no razão</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <div class="modal-body">
      @if (resource.isLoading()) {
        <app-skeleton variant="lines" [rows]="6" />
      } @else if (resource.status() === 'error') {
        <app-empty-state icon="cloud-slash" tone="error" title="Não foi possível carregar o recebimento." [compact]="true" />
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
          <div class="alert alert-warning d-flex align-items-start gap-2" role="note"><app-icon name="hourglass-top" /><span>Aguardando regra de comissão: será apurado quando a regra vigente na data do pagamento for publicada.</span></div>
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
    </div>
    <div class="modal-footer">
      <button class="btn btn-link" type="button" appDialogClose>Fechar</button>
    </div>
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
  protected readonly data = inject<ReceiptDialogData>(DIALOG_DATA);
  private readonly api = inject(CommissionLedgerApi);
  protected readonly paymentTypes = PAYMENT_TYPE_LABELS;
  protected readonly received = RECEIVED_BY_LABELS;
  protected readonly pending = PENDING_REASON_LABELS;
  protected readonly resource = rxResource({ stream: () => this.api.get(this.data.receiptId) });
  protected readonly detail = valueOr(this.resource, undefined);
}
