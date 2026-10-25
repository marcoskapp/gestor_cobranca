import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { NgbDateStruct, NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { isoDate } from '../../../shared/models/dates';
import { AppValidators } from '../../../shared/validation/validators';
import { zonedNow } from '../../collection/data/collection.rules';
import { EVIDENCE_ACCEPT, evidenceProblem, parseMoney } from '../../payment/data/payment.rules';
import { TransfersApi, newIdempotencyKey } from '../data/settlement.api';
import { SETTLEMENT_ERROR_LABELS } from '../data/settlement.labels';
import { StatementBalance, TransferView } from '../data/settlement.models';
import { periodText } from '../data/settlement.rules';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';

/** `yyyy-MM-dd` → limite do ngbDatepicker. */
function dateStruct(iso: string): NgbDateStruct {
  const [year, month, day] = iso.split('-').map(Number);
  return { year, month, day };
}

export interface TransferDialogData {
  creditorName: string;
  statement: Pick<StatementBalance, 'statementId' | 'sequenceNumber' | 'periodStart' | 'periodEnd' | 'outstanding'>;
}

/**
 * Registro de repasse (ou de recebimento do credor, quando o líquido é negativo) com o comprovante
 * bancário obrigatório. O valor vai até o saldo do demonstrativo; reenvio com a mesma chave não
 * duplica.
 */
@Component({
  selector: 'app-transfer-dialog',
  imports: [ReactiveFormsModule, NgbInputDatepicker, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Amount, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ toCreditor ? 'Registrar repasse ao credor' : 'Registrar recebimento do credor' }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form [formGroup]="form" (ngSubmit)="send()" novalidate>
      <div class="modal-body">
        <dl class="app-definition-list td-dl">
          <dt>Credor</dt>
          <dd>{{ data.creditorName }}</dd>
          <dt>Demonstrativo</dt>
          <dd>nº {{ data.statement.sequenceNumber }} · {{ period }}</dd>
          <dt>{{ toCreditor ? 'Saldo a repassar' : 'Saldo a receber' }}</dt>
          <dd><app-amount [value]="balance" /></dd>
        </dl>
        <div class="app-form-grid">
          <app-field label="Valor (R$)" hint="Parcial permitido, até o saldo" error="Valor entre R$ 0,01 e o saldo.">
            <input appInput formControlName="amount" inputmode="decimal" required />
          </app-field>
          <app-field label="Data da transferência" error="Entre o fim do período e hoje.">
            <div class="input-group">
              <input appInput ngbDatepicker #picker="ngbDatepicker" formControlName="date" [minDate]="minDate" [maxDate]="maxDate" placeholder="dd/mm/aaaa" autocomplete="off" required />
              <button type="button" class="btn btn-outline-secondary" aria-label="Abrir calendário" (click)="picker.toggle()">
                <app-icon name="calendar-event" />
              </button>
            </div>
          </app-field>
          <app-field class="app-span-all" label="Referência bancária" hint="Ex.: número da TED ou id fim a fim do Pix" error="Informe a referência bancária.">
            <input appInput formControlName="reference" maxlength="120" required />
          </app-field>
          <app-field class="app-span-all" label="Observação (opcional)">
            <textarea appInput formControlName="note" rows="2" maxlength="1000"></textarea>
          </app-field>
        </div>
        <div class="td-file" data-tour="transfer-evidence">
          <input #input type="file" [accept]="accept" (change)="pick(input.files); input.value = ''" hidden />
          <button class="btn btn-outline-primary" type="button" (click)="input.click()">
            <app-icon name="paperclip" />
            {{ file() ? 'Trocar comprovante' : 'Anexar comprovante' }}
          </button>
          @if (file(); as f) {
            <span>{{ f.name }}</span>
          } @else {
            <span class="app-muted">PDF, PNG ou JPEG até 10 MB (obrigatório)</span>
          }
        </div>
        @if (fileError()) {
          <div class="invalid-feedback d-block" role="alert">{{ fileError() }}</div>
        } @else if (submitted() && !file()) {
          <div class="invalid-feedback d-block" role="alert">Anexe o comprovante bancário.</div>
        }
        @if (error()) {
          <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="sending()">Registrar</button>
      </div>
    </form>
  `,
  styles: `
    .td-dl {
      margin-bottom: 12px;
    }
    .td-file {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 8px 12px;
    }
  `,
})
export class TransferDialog {
  protected readonly data = inject<TransferDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<TransferView>>(DialogRef);
  private readonly api = inject(TransfersApi);
  /** Uma chave por intenção: repetir o envio depois de uma falha não duplica o repasse. */
  private readonly key = newIdempotencyKey();
  protected readonly accept = EVIDENCE_ACCEPT;
  protected readonly toCreditor = this.data.statement.outstanding > 0;
  protected readonly balance = Math.abs(this.data.statement.outstanding);
  protected readonly period = periodText(this.data.statement.periodStart, this.data.statement.periodEnd);
  protected readonly minDate = dateStruct(this.data.statement.periodEnd);
  protected readonly maxDate = dateStruct(zonedNow().date);
  protected readonly file = signal<File | null>(null);
  protected readonly fileError = signal<string | null>(null);
  protected readonly submitted = signal(false);
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    amount: [
      formatAmount(this.balance).replace('R$', '').trim(),
      [AppValidators.notBlank, (c) => {
        const v = parseMoney(c.value as string);
        return v === null || v <= 0 || v > this.balance + 0.001 ? { money: true } : null;
      }],
    ],
    date: this.fb.control<string | null>(zonedNow().date, [Validators.required]),
    reference: ['', [AppValidators.notBlank, Validators.maxLength(120)]],
    note: ['', [Validators.maxLength(1000)]],
  });

  protected pick(files: FileList | null): void {
    const f = files?.[0];
    if (!f) {
      return;
    }
    const problem = evidenceProblem(f);
    this.fileError.set(problem);
    this.file.set(problem ? null : f);
  }

  protected send(): void {
    this.submitted.set(true);
    const v = this.form.getRawValue();
    const file = this.file();
    const date = isoDate(v.date);
    if (this.form.invalid || !file || !date || this.sending()) {
      this.form.markAllAsTouched();
      return;
    }
    this.sending.set(true);
    this.error.set(null);
    this.api
      .register({ statementId: this.data.statement.statementId, amount: parseMoney(v.amount)!, transferredOn: date, bankReference: v.reference.trim(), note: v.note.trim() || null }, file, this.key)
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({
        next: (t) => this.ref.close(t),
        error: (err: unknown) => {
          const e = toApiError(err);
          this.error.set((e.code && SETTLEMENT_ERROR_LABELS[e.code]) || userMessage(e));
        },
      });
  }
}
