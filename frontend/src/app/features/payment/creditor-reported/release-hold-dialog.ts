import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { formatAmount } from '../../../shared/components/amount/amount';
import { Icon } from '../../../shared/components/icon/icon';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { AppValidators } from '../../../shared/validation/validators';
import { HeldReversalItem, HoldReleaseReason, ReversalHoldsApi } from './reversal-holds.api';
import { HOLD_ERROR_LABELS, HOLD_NOTE_MAX, HOLD_RELEASE_REASONS, HOLD_RELEASE_REASON_LABELS } from './reversal-holds.rules';

export interface ReleaseHoldDialogData {
  item: HeldReversalItem;
  creditorName: string;
}

/**
 * "Liberar a cobrança" de um estorno em conferência (decisão do responsável de 26/11/2026): motivo em lista curta,
 * justificativa obrigatória e a confirmação de que conferiu com o credor. O servidor confere a permissão, a segregação
 * (quem estornou ou informou não libera) e a situação sob trava.
 */
@Component({
  selector: 'app-release-hold-dialog',
  imports: [Icon, ...FORM_FIELD, ...DIALOG_DIRECTIVES, ReactiveFormsModule, AppDatePipe],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Liberar a cobrança</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="send()" novalidate>
      <div class="modal-body">
        <p class="app-dialog-message">Os títulos e a parcela voltam à cobrança (fila, régua e portal), respeitando a política de contato.</p>
        <dl class="app-definition-list bg-body-tertiary rounded p-3 mb-3">
          <dt>Credor</dt>
          <dd>{{ data.creditorName }}</dd>
          @if (data.item.hold.debtorName) {
            <dt>Devedor</dt>
            <dd>{{ data.item.hold.debtorName }}</dd>
          }
          <dt>Pagamento estornado</dt>
          <dd>{{ amount }}</dd>
          <dt>Estornado em</dt>
          <dd>{{ data.item.hold.placedAt | appDate: 'date' }}</dd>
          <dt>Volta à cobrança</dt>
          <dd>{{ returning }}</dd>
        </dl>
        @if (error()) {
          <div class="alert alert-danger d-flex gap-2 align-items-start" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
        <app-field class="mb-3" label="Motivo" error="Escolha o motivo.">
          <select appInput formControlName="reason" required>
            <option value="" disabled>Escolha</option>
            @for (r of reasons; track r) {
              <option [ngValue]="r">{{ labels[r] }}</option>
            }
          </select>
        </app-field>
        <app-field class="mb-3" label="Justificativa" hint="Não inclua dados pessoais do devedor." error="Escreva a justificativa (até 500 caracteres).">
          <textarea appInput formControlName="note" rows="3" [attr.maxlength]="noteMax" required aria-describedby="hold-note-count"></textarea>
        </app-field>
        <p class="app-muted small text-end mt-n2 mb-3" id="hold-note-count" aria-live="polite">{{ noteLength() }} de {{ noteMax }} caracteres</p>
        <div class="form-check">
          <input class="form-check-input" type="checkbox" id="hold-confirm" formControlName="confirmed" />
          <label class="form-check-label" for="hold-confirm">Conferi com o credor que este valor está em aberto</label>
        </div>
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose ngbAutofocus>Cancelar</button>
        <button type="submit" class="btn btn-primary" [disabled]="sending()">Liberar a cobrança</button>
      </div>
    </form>
  `,
})
export class ReleaseHoldDialog {
  protected readonly data = inject<ReleaseHoldDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<HeldReversalItem>>(DialogRef);
  private readonly api = inject(ReversalHoldsApi);
  protected readonly reasons = HOLD_RELEASE_REASONS;
  protected readonly labels = HOLD_RELEASE_REASON_LABELS;
  protected readonly noteMax = HOLD_NOTE_MAX;
  protected readonly amount = this.data.item.amount === null ? '—' : formatAmount(this.data.item.amount);
  /** O que volta à cobrança com a liberação. */
  protected readonly returning = (() => {
    const h = this.data.item.hold;
    const titles = h.titleCount === 1 ? '1 título' : `${h.titleCount} títulos`;
    if (!h.installmentCount) {
      return titles;
    }
    return `${titles} e ${h.installmentCount === 1 ? 'a parcela' : 'as parcelas'} do acordo`;
  })();
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    reason: this.fb.control<HoldReleaseReason | ''>('', [Validators.required]),
    note: ['', [AppValidators.notBlank, Validators.maxLength(HOLD_NOTE_MAX)]],
    confirmed: [false],
  });
  private readonly note = toSignal(this.form.controls.note.valueChanges, { initialValue: '' });
  protected readonly noteLength = computed(() => this.note().length);

  protected send(): void {
    const v = this.form.getRawValue();
    if (this.form.invalid || !v.reason || this.sending()) {
      this.form.markAllAsTouched();
      return;
    }
    if (!v.confirmed) {
      this.error.set(HOLD_ERROR_LABELS['reversal_hold.confirmation_required']);
      return;
    }
    this.sending.set(true);
    this.error.set(null);
    this.api
      .release(this.data.item.hold.id, { version: this.data.item.hold.version, reasonCode: v.reason, note: v.note.trim(), confirmedWithCreditor: true })
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({
        next: (item) => this.ref.close(item),
        error: (err: unknown) => {
          const e = toApiError(err);
          this.error.set((e.code && HOLD_ERROR_LABELS[e.code]) || userMessage(e));
        },
      });
  }
}
