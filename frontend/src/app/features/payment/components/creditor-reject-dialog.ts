import { Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppValidators } from '../../../shared/validation/validators';
import { CREDITOR_REJECTION_REASON_LABELS, CREDITOR_REJECTION_REASON_PORTAL_TEXT } from '../data/payment.labels';
import { CreditorRejectionReason } from '../data/payment.models';

export interface CreditorRejection {
  creditorReason: CreditorRejectionReason;
  note: string;
}

/**
 * Recusa do pagamento informado pela pessoa do credor no portal: o motivo que o credor vê (lista fechada) e a nota
 * interna da revisão, que nunca vai ao credor.
 */
@Component({
  selector: 'app-creditor-reject-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Recusar pagamento informado pelo credor</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="submit()" novalidate>
      <div class="modal-body">
        <p class="app-dialog-message">
          O pagamento não baixa a dívida. O credor vê no portal só o motivo escolhido abaixo; a observação fica com a assessoria.
        </p>
        <app-field class="mb-3" label="Motivo que o credor vê" error="Escolha o motivo.">
          <select appInput ngbAutofocus formControlName="creditorReason" required>
            <option value="" disabled>Selecione</option>
            @for (r of reasons; track r[0]) {
              <option [value]="r[0]">{{ r[1] }}</option>
            }
          </select>
        </app-field>
        @if (form.controls.creditorReason.value; as r) {
          <div class="app-muted small mb-3" aria-live="polite">O credor vai ler: “{{ portalText[r] }}”</div>
        }
        <app-field label="Observação interna" hint="Só a assessoria vê." error="Descreva o motivo da recusa.">
          <textarea appInput formControlName="note" rows="3" maxlength="1000" required></textarea>
        </app-field>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-danger" type="submit">Recusar</button>
      </div>
    </form>
  `,
})
export class CreditorRejectDialog {
  private readonly ref = inject<DialogRef<CreditorRejection>>(DialogRef);
  protected readonly reasons = Object.entries(CREDITOR_REJECTION_REASON_LABELS) as [CreditorRejectionReason, string][];
  protected readonly portalText = CREDITOR_REJECTION_REASON_PORTAL_TEXT;
  protected readonly form = inject(FormBuilder).nonNullable.group({
    creditorReason: ['' as CreditorRejectionReason | '', [Validators.required]],
    note: ['', [AppValidators.notBlank, Validators.maxLength(1000)]],
  });

  protected submit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }
    const v = this.form.getRawValue();
    this.ref.close({ creditorReason: v.creditorReason as CreditorRejectionReason, note: v.note.trim() });
  }
}
