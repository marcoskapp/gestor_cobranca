import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { formatAmount } from '../../../shared/components/amount/amount';
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
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule],
  template: `
    <h2 mat-dialog-title class="app-confirm__title"><mat-icon class="app-confirm__icon" aria-hidden="true">warning_amber</mat-icon>Estornar pagamento</h2>
    <form [formGroup]="form" (ngSubmit)="send()" novalidate>
      <mat-dialog-content>
        <p class="app-dialog-message">O pagamento deixa de abater a dívida: cada destino é revertido e as parcelas ou títulos voltam a ter saldo em aberto.</p>
        <dl class="app-definition-list app-confirm__details">
          <dt>Devedor</dt>
          <dd>{{ data.debtorName }}</dd>
          <dt>Valor</dt>
          <dd>−{{ amount }}</dd>
          <dt>Destinos revertidos</dt>
          <dd>{{ targets }}</dd>
        </dl>
        @if (error()) {
          <p class="app-alert app-alert--error" role="alert"><mat-icon aria-hidden="true">error_outline</mat-icon><span>{{ error() }}</span></p>
        }
        <mat-form-field class="app-full-width">
          <mat-label>Motivo</mat-label>
          <mat-select formControlName="reason" required>
            @for (r of reasons; track r) {
              <mat-option [value]="r">{{ labels[r] }}</mat-option>
            }
          </mat-select>
          <mat-error>Escolha o motivo.</mat-error>
        </mat-form-field>
        <mat-form-field class="app-full-width">
          <mat-label>Justificativa</mat-label>
          <textarea matInput formControlName="note" rows="3" maxlength="1000" required></textarea>
          <mat-hint>Registrada na auditoria.</mat-hint>
          <mat-error>Descreva o motivo do estorno.</mat-error>
        </mat-form-field>
        <p class="app-alert app-alert--warning" role="note">
          <mat-icon aria-hidden="true">info</mat-icon>
          <span>O estorno não pode ser desfeito. Se o dinheiro foi para o destino errado, prefira realocar.</span>
        </p>
        <mat-form-field class="app-full-width">
          <mat-label>Digite {{ word }} para confirmar</mat-label>
          <input matInput formControlName="confirmation" autocomplete="off" spellcheck="false" />
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close cdkFocusInitial>Cancelar</button>
        <button mat-flat-button type="submit" class="app-button-danger" [disabled]="!confirmed() || sending()">Estornar</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .app-confirm__title {
      display: flex;
      align-items: center;
      gap: 8px;
    }
    .app-confirm__icon {
      color: var(--app-danger-fg);
    }
    .app-confirm__details {
      margin: 0 0 16px;
      padding: 12px;
      border-radius: var(--app-radius-md);
      background: var(--app-surface-raised);
    }
  `,
})
export class ReverseDialog {
  protected readonly data = inject<ReverseDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<ReverseDialog, PaymentView>>(MatDialogRef);
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
