import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDatepickerModule } from '@angular/material/datepicker';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { finalize } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { fromLocalDate, toLocalDate } from '../../../shared/models/dates';
import { AppValidators } from '../../../shared/validation/validators';
import { zonedNow } from '../../collection/data/collection.rules';
import { EVIDENCE_ACCEPT, evidenceProblem, parseMoney } from '../../payment/data/payment.rules';
import { TransfersApi, newIdempotencyKey } from '../data/settlement.api';
import { SETTLEMENT_ERROR_LABELS } from '../data/settlement.labels';
import { StatementBalance, TransferView } from '../data/settlement.models';
import { periodText } from '../data/settlement.rules';

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
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatDatepickerModule, MatFormFieldModule, MatIconModule, MatInputModule, Amount],
  template: `
    <h2 mat-dialog-title>{{ toCreditor ? 'Registrar repasse ao credor' : 'Registrar recebimento do credor' }}</h2>
    <form [formGroup]="form" (ngSubmit)="send()" novalidate>
      <mat-dialog-content>
        <dl class="app-definition-list td-dl">
          <dt>Credor</dt>
          <dd>{{ data.creditorName }}</dd>
          <dt>Demonstrativo</dt>
          <dd>nº {{ data.statement.sequenceNumber }} · {{ period }}</dd>
          <dt>{{ toCreditor ? 'Saldo a repassar' : 'Saldo a receber' }}</dt>
          <dd><app-amount [value]="balance" /></dd>
        </dl>
        <div class="app-form-grid">
          <mat-form-field>
            <mat-label>Valor (R$)</mat-label>
            <input matInput formControlName="amount" inputmode="decimal" required />
            <mat-hint>Parcial permitido, até o saldo</mat-hint>
            <mat-error>Valor entre R$ 0,01 e o saldo.</mat-error>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Data da transferência</mat-label>
            <input matInput [matDatepicker]="picker" formControlName="date" [min]="minDate" [max]="maxDate" required />
            <mat-datepicker-toggle matIconSuffix [for]="picker" />
            <mat-datepicker #picker />
            <mat-error>Entre o fim do período e hoje.</mat-error>
          </mat-form-field>
          <mat-form-field class="app-span-all">
            <mat-label>Referência bancária</mat-label>
            <input matInput formControlName="reference" maxlength="120" required />
            <mat-hint>Ex.: número da TED ou id fim a fim do Pix</mat-hint>
            <mat-error>Informe a referência bancária.</mat-error>
          </mat-form-field>
          <mat-form-field class="app-span-all">
            <mat-label>Observação (opcional)</mat-label>
            <textarea matInput formControlName="note" rows="2" maxlength="1000"></textarea>
          </mat-form-field>
        </div>
        <div class="td-file" data-tour="transfer-evidence">
          <input #input type="file" [accept]="accept" (change)="pick(input.files); input.value = ''" hidden />
          <button mat-stroked-button type="button" (click)="input.click()">
            <mat-icon aria-hidden="true">attach_file</mat-icon>
            {{ file() ? 'Trocar comprovante' : 'Anexar comprovante' }}
          </button>
          @if (file(); as f) {
            <span>{{ f.name }}</span>
          } @else {
            <span class="app-muted">PDF, PNG ou JPEG até 10 MB (obrigatório)</span>
          }
        </div>
        @if (fileError()) {
          <p class="app-field-error" role="alert">{{ fileError() }}</p>
        } @else if (submitted() && !file()) {
          <p class="app-field-error" role="alert">Anexe o comprovante bancário.</p>
        }
        @if (error()) {
          <p class="app-alert app-alert--error" role="alert"><mat-icon aria-hidden="true">error_outline</mat-icon><span>{{ error() }}</span></p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="sending()">Registrar</button>
      </mat-dialog-actions>
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
  protected readonly data = inject<TransferDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<TransferDialog, TransferView>>(MatDialogRef);
  private readonly api = inject(TransfersApi);
  /** Uma chave por intenção: repetir o envio depois de uma falha não duplica o repasse. */
  private readonly key = newIdempotencyKey();
  protected readonly accept = EVIDENCE_ACCEPT;
  protected readonly toCreditor = this.data.statement.outstanding > 0;
  protected readonly balance = Math.abs(this.data.statement.outstanding);
  protected readonly period = periodText(this.data.statement.periodStart, this.data.statement.periodEnd);
  protected readonly minDate = fromLocalDate(this.data.statement.periodEnd);
  protected readonly maxDate = fromLocalDate(zonedNow().date);
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
    date: this.fb.control<Date | null>(fromLocalDate(zonedNow().date), [Validators.required]),
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
    const date = toLocalDate(v.date);
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
