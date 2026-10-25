import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
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
  imports: [Icon, ...FORM_FIELD, ...DIALOG_DIRECTIVES, ReactiveFormsModule, Amount, PaymentTarget],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Realocar valor · {{ data.debtorName }}</h2>
      <button type="button" class="btn-close" aria-label="Fechar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="send()" novalidate>
      <div class="modal-body">
        @if (error()) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
        <h3 class="app-section-title">De onde sai o valor</h3>
        <fieldset class="mb-2">
          <legend class="visually-hidden">Origem do valor</legend>
          @for (s of sources; track s.allocationId; let i = $index) {
            <div class="form-check">
              <input class="form-check-input" type="radio" formControlName="source" [value]="i" [id]="'rd-source-' + i" [attr.ngbAutofocus]="i === 0 ? '' : null" />
              <label class="form-check-label" [for]="'rd-source-' + i">{{ s.label }} · <app-amount [value]="s.available" /></label>
            </div>
          }
        </fieldset>

        <h3 class="app-section-title">Para onde vai</h3>
        <div class="btn-group mb-2" role="group" aria-label="Destino">
          <input type="radio" class="btn-check" formControlName="mode" value="TARGET" id="rd-mode-target" />
          <label class="btn btn-outline-primary" for="rd-mode-target">Outro destino</label>
          <input type="radio" class="btn-check" formControlName="mode" value="REFUND" id="rd-mode-refund" />
          <label class="btn btn-outline-primary" for="rd-mode-refund">Devolver ao pagador</label>
        </div>
        @if (form.controls.mode.value === 'TARGET') {
          <app-payment-target [debtorId]="data.payment.debtorId" [creditorId]="data.payment.creditorId" mode="allocation" [(selection)]="target" />
          @if (submitted() && !target()) {
            <div class="invalid-feedback d-block" role="alert">Escolha o acordo ou os títulos que recebem o valor.</div>
          }
        } @else {
          <p class="app-muted rd-small">Faça a transferência ao pagador pelo banco e registre aqui o identificador (E2E do Pix, número do DOC/TED).</p>
          <app-field class="mb-3" label="Referência da devolução" error="Informe a referência da transferência de devolução.">
            <input appInput formControlName="refundReference" maxlength="100" autocomplete="off" spellcheck="false" />
          </app-field>
        }

        <div class="app-form-grid mb-3">
          <app-field label="Valor" hint="Disponível: {{ money(available()) }}" error="Informe um valor até o disponível.">
            <div class="input-group">
              <span class="input-group-text">R$</span>
              <input appInput formControlName="amount" inputmode="decimal" autocomplete="off" required />
            </div>
          </app-field>
        </div>
        <app-field class="mb-3" label="Justificativa" hint="Registrada na auditoria." error="Explique a realocação.">
          <textarea appInput formControlName="note" rows="2" maxlength="1000" required></textarea>
        </app-field>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="sending() || !sources.length">{{ form.controls.mode.value === 'REFUND' ? 'Registrar devolução' : 'Realocar' }}</button>
      </div>
    </form>
  `,
  styles: `
    .rd-small {
      font-size: var(--app-text-sm);
    }
  `,
})
export class ReallocateDialog {
  protected readonly data = inject<ReallocateDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<PaymentView>>(DialogRef);
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
