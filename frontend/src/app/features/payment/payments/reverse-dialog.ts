import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { formatAmount } from '../../../shared/components/amount/amount';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppValidators } from '../../../shared/validation/validators';
import { PaymentsApi } from '../data/payment.api';
import { MANUAL_REVERSAL_REASONS, PAYMENT_ERROR_LABELS, REVERSAL_REASON_LABELS } from '../data/payment.labels';
import { PaymentView, ReversalReason } from '../data/payment.models';
import { allocationTarget } from '../data/payment.rules';

export interface ReverseDialogData {
  payment: PaymentView;
  debtorName: string;
}

/** Palavra que confirma o estorno (ação financeira irreversível). */
export const REVERSE_WORD = 'ESTORNAR';

/**
 * Estorno compensatório: cada alocação ativa é revertida (títulos recebem o lançamento espelho,
 * acordos recebem o evento de estorno). Exige motivo, justificativa e a palavra de confirmação.
 */
@Component({
  selector: 'app-reverse-dialog',
  imports: [Icon, ...FORM_FIELD, ...DIALOG_DIRECTIVES, ReactiveFormsModule],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0 d-flex align-items-center gap-2"><app-icon class="text-danger-emphasis" name="exclamation-triangle" />Estornar pagamento</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="send()" novalidate>
      <div class="modal-body">
        <p class="app-dialog-message">O pagamento deixa de abater a dívida: cada destino é revertido e as parcelas ou títulos voltam a ter saldo em aberto.</p>
        <dl class="app-definition-list bg-body-tertiary rounded p-3 mb-3">
          <dt>Devedor</dt>
          <dd>{{ data.debtorName }}</dd>
          <dt>Valor</dt>
          <dd>−{{ amount }}</dd>
          <dt>Destinos revertidos</dt>
          <dd>{{ targets }}</dd>
        </dl>
        @if (error()) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
        <app-field class="mb-3" label="Motivo" error="Escolha o motivo.">
          <select appInput formControlName="reason" required>
            @for (r of reasons; track r) {
              <option [ngValue]="r">{{ labels[r] }}</option>
            }
          </select>
        </app-field>
        <app-field class="mb-3" label="Justificativa" hint="Registrada na auditoria." error="Descreva o motivo do estorno.">
          <textarea appInput formControlName="note" rows="3" maxlength="1000" required></textarea>
        </app-field>
        <div class="alert alert-warning d-flex gap-2 align-items-start" role="note">
          <app-icon name="info-circle" />
          <span>O estorno não pode ser desfeito. Se o dinheiro foi para o destino errado, prefira realocar.</span>
        </div>
        <app-field class="mb-3" label="Digite {{ word }} para confirmar">
          <input appInput formControlName="confirmation" autocomplete="off" spellcheck="false" />
        </app-field>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose ngbAutofocus>Cancelar</button>
        <button type="submit" class="btn btn-danger" [disabled]="!confirmed() || sending()">Estornar</button>
      </div>
    </form>
  `,
})
export class ReverseDialog {
  protected readonly data = inject<ReverseDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<PaymentView>>(DialogRef);
  private readonly api = inject(PaymentsApi);
  protected readonly reasons = MANUAL_REVERSAL_REASONS;
  protected readonly labels = REVERSAL_REASON_LABELS;
  protected readonly word = REVERSE_WORD;
  protected readonly amount = formatAmount(this.data.payment.amount);
  protected readonly targets =
    this.data.payment.allocations
      .filter((a) => a.status === 'ACTIVE')
      .map(allocationTarget)
      .join('; ') || 'Somente a sobra do pagamento';
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    reason: this.fb.control<ReversalReason | ''>('', [Validators.required]),
    note: ['', [AppValidators.notBlank, Validators.maxLength(1000)]],
    confirmation: [''],
  });
  private readonly typed = toSignal(this.form.controls.confirmation.valueChanges, { initialValue: '' });
  protected readonly confirmed = computed(() => this.typed().trim().toUpperCase() === REVERSE_WORD);

  protected send(): void {
    const v = this.form.getRawValue();
    if (this.form.invalid || !this.confirmed() || !v.reason || this.sending()) {
      this.form.markAllAsTouched();
      return;
    }
    this.sending.set(true);
    this.error.set(null);
    this.api
      .reverse(this.data.payment.id, { reason: v.reason, note: v.note.trim() })
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({
        next: (payment) => this.ref.close(payment),
        error: (err: unknown) => {
          const e = toApiError(err);
          this.error.set((e.code && PAYMENT_ERROR_LABELS[e.code]) || userMessage(e));
        },
      });
  }
}
