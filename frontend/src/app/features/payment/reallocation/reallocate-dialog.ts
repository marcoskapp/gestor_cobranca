import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { AppValidators } from '../../../shared/validation/validators';
import { PaymentTarget, TargetSelection } from '../components/payment-target';
import { PaymentsApi } from '../data/payment.api';
import { PAYMENT_ERROR_LABELS } from '../data/payment.labels';
import { PaymentView, ReallocateRequest } from '../data/payment.models';
import { ReallocationSource, parseMoney, reallocationSources, toTargetRequest } from '../data/payment.rules';

export interface ReallocateDialogData {
  payment: PaymentView;
  debtorName: string;
}

/**
 * Realocação de sobra ou de valor não aplicado num acordo: para outro acordo/títulos do mesmo
 * devedor e credor, ou devolução ao pagador com a referência da transferência. Justificativa
 * obrigatória (auditada).
 */
@Component({
  selector: 'app-reallocate-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatButtonToggleModule, MatFormFieldModule, MatIconModule, MatInputModule, MatRadioModule, Amount, PaymentTarget],
  template: `
    <h2 mat-dialog-title>Realocar valor · {{ data.debtorName }}</h2>
    <form [formGroup]="form" (ngSubmit)="send()" novalidate>
      <mat-dialog-content>
        @if (error()) {
          <p class="app-alert app-alert--error" role="alert"><mat-icon aria-hidden="true">error_outline</mat-icon><span>{{ error() }}</span></p>
        }
        <h3 class="app-section-title">De onde sai o valor</h3>
        <mat-radio-group formControlName="source" class="rd-sources" aria-label="Origem do valor">
          @for (s of sources; track s.allocationId; let i = $index) {
            <mat-radio-button [value]="i">
              {{ s.label }} · <app-amount [value]="s.available" />
            </mat-radio-button>
          }
        </mat-radio-group>

        <h3 class="app-section-title">Para onde vai</h3>
        <mat-button-toggle-group formControlName="mode" aria-label="Destino" class="rd-mode">
          <mat-button-toggle value="TARGET">Outro destino</mat-button-toggle>
          <mat-button-toggle value="REFUND">Devolver ao pagador</mat-button-toggle>
        </mat-button-toggle-group>
        @if (form.controls.mode.value === 'TARGET') {
          <app-payment-target [debtorId]="data.payment.debtorId" [creditorId]="data.payment.creditorId" mode="allocation" [(selection)]="target" />
          @if (submitted() && !target()) {
            <p class="app-field-error" role="alert">Escolha o acordo ou os títulos que recebem o valor.</p>
          }
        } @else {
          <p class="app-muted rd-small">Faça a transferência ao pagador pelo banco e registre aqui o identificador (E2E do Pix, número do DOC/TED).</p>
          <mat-form-field class="app-full-width">
            <mat-label>Referência da devolução</mat-label>
            <input matInput formControlName="refundReference" maxlength="100" autocomplete="off" spellcheck="false" />
            <mat-error>Informe a referência da transferência de devolução.</mat-error>
          </mat-form-field>
        }

        <div class="app-form-grid">
          <mat-form-field>
            <mat-label>Valor</mat-label>
            <span matTextPrefix>R$&nbsp;</span>
            <input matInput formControlName="amount" inputmode="decimal" autocomplete="off" required />
            <mat-hint>Disponível: {{ money(available()) }}</mat-hint>
            <mat-error>Informe um valor até o disponível.</mat-error>
          </mat-form-field>
        </div>
        <mat-form-field class="app-full-width">
          <mat-label>Justificativa</mat-label>
          <textarea matInput formControlName="note" rows="2" maxlength="1000" required></textarea>
          <mat-hint>Registrada na auditoria.</mat-hint>
          <mat-error>Explique a realocação.</mat-error>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="sending() || !sources.length">{{ form.controls.mode.value === 'REFUND' ? 'Registrar devolução' : 'Realocar' }}</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .rd-sources {
      display: flex;
      flex-direction: column;
      margin-bottom: 8px;
    }
    .rd-mode {
      margin-bottom: 8px;
      flex-wrap: wrap;
    }
    .rd-small {
      font-size: var(--app-text-sm);
    }
  `,
})
export class ReallocateDialog {
  protected readonly data = inject<ReallocateDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<ReallocateDialog, PaymentView>>(MatDialogRef);
  private readonly api = inject(PaymentsApi);
  protected readonly sources: ReallocationSource[] = reallocationSources(this.data.payment);
  protected readonly money = formatAmount;
  protected readonly target = signal<TargetSelection | null>(null);
  protected readonly sending = signal(false);
  protected readonly submitted = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    source: [0],
    mode: this.fb.control<'TARGET' | 'REFUND'>('TARGET'),
    refundReference: ['', [Validators.maxLength(100)]],
    amount: [this.sources[0] ? String(this.sources[0].available).replace('.', ',') : '', [AppValidators.notBlank]],
    note: ['', [AppValidators.notBlank, Validators.maxLength(1000)]],
  });
  private readonly sourceIndex = toSignal(this.form.controls.source.valueChanges, { initialValue: 0 });
  protected readonly available = computed(() => this.sources[this.sourceIndex()]?.available ?? 0);

  constructor() {
    this.form.controls.amount.addValidators((c) => {
      const v = parseMoney(c.value);
      return v === null || v <= 0 || v > this.available() ? { money: true } : null;
    });
    this.form.controls.source.valueChanges.subscribe((i) => {
      const s = this.sources[i];
      if (s) {
        this.form.controls.amount.setValue(String(s.available).replace('.', ','));
      }
    });
  }

  protected send(): void {
    this.submitted.set(true);
    const v = this.form.getRawValue();
    const refund = v.mode === 'REFUND';
    const target = this.target();
    if (refund && !v.refundReference.trim()) {
      this.form.controls.refundReference.setErrors({ required: true });
    }
    if (this.form.invalid || (!refund && !target) || this.sending()) {
      this.form.markAllAsTouched();
      return;
    }
    const source = this.sources[v.source];
    const request: ReallocateRequest = {
      sourceAllocationId: source.allocationId,
      amount: parseMoney(v.amount)!,
      target: refund ? null : toTargetRequest(target!),
      refundReference: refund ? v.refundReference.trim() : null,
      note: v.note.trim(),
    };
    this.sending.set(true);
    this.error.set(null);
    this.api
      .reallocate(this.data.payment.id, request)
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
