import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { finalize, startWith } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { AppValidators } from '../../../shared/validation/validators';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { parseMoney } from '../../payment/data/payment.rules';
import { StatementsApi } from '../data/settlement.api';
import { ADJUSTMENT_CATEGORY_LABELS, EXPORT_FORMAT_LABELS, SEND_CHANNEL_LABELS, SETTLEMENT_ERROR_LABELS } from '../data/settlement.labels';
import { AdjustmentCategory, AdjustmentView, ExportView, SendChannel, StatementView } from '../data/settlement.models';
import { ADJUSTMENT_DUAL_APPROVAL_THRESHOLD, ADJUSTMENT_REASON_MIN, needsSecondApproval } from '../data/settlement.rules';

const errorText = (err: unknown) => {
  const e = toApiError(err);
  return (e.code && SETTLEMENT_ERROR_LABELS[e.code]) || userMessage(e);
};

export interface SendDialogData {
  statement: StatementView;
  exports: readonly ExportView[];
}

/** Registra o envio ao credor: canal, referência e os arquivos enviados (com o SHA-256 de cada um). */
@Component({
  selector: 'app-send-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatCheckboxModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule, AppDatePipe],
  template: `
    <h2 mat-dialog-title>Registrar envio ao credor</h2>
    <form [formGroup]="form" (ngSubmit)="send()" novalidate>
      <mat-dialog-content>
        <p class="app-dialog-message">O envio é feito fora do sistema (e-mail, portal do credor…). Registre aqui o canal, a referência e quais arquivos foram enviados.</p>
        <mat-form-field class="app-full-width">
          <mat-label>Canal</mat-label>
          <mat-select formControlName="channel" required>
            @for (c of channels; track c[0]) {
              <mat-option [value]="c[0]">{{ c[1] }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        <mat-form-field class="app-full-width">
          <mat-label>Referência (opcional)</mat-label>
          <input matInput formControlName="reference" maxlength="200" />
          <mat-hint>Ex.: protocolo do e-mail ou número do chamado no credor</mat-hint>
        </mat-form-field>
        <fieldset class="sd-files">
          <legend>Arquivos enviados</legend>
          @for (x of data.exports; track x.id) {
            <mat-checkbox [checked]="picked().has(x.id)" (change)="toggle(x.id)">
              {{ formats[x.format] }} · {{ x.fileName }}
              <span class="app-muted sd-small">gerado em {{ x.createdAt | appDate }}</span>
            </mat-checkbox>
          }
        </fieldset>
        @if (error()) {
          <p class="app-alert app-alert--error" role="alert"><mat-icon aria-hidden="true">error_outline</mat-icon><span>{{ error() }}</span></p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="!picked().size || form.invalid || sending()">Registrar envio</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    .sd-files {
      display: grid;
      gap: 4px;
      margin: 0;
      padding: 8px 12px;
      border: 1px solid var(--app-border);
      border-radius: var(--app-radius-md);
    }
    .sd-small {
      margin-left: 6px;
      font-size: var(--app-text-xs);
    }
  `,
})
export class SendDialog {
  protected readonly data = inject<SendDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<SendDialog, StatementView>>(MatDialogRef);
  private readonly api = inject(StatementsApi);
  protected readonly channels = Object.entries(SEND_CHANNEL_LABELS) as [SendChannel, string][];
  protected readonly formats = EXPORT_FORMAT_LABELS;
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);
  /** Por padrão, o último arquivo de cada formato. */
  protected readonly picked = signal(new Set(latestByFormat(this.data.exports).map((x) => x.id)));

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    channel: this.fb.control<SendChannel>('EMAIL', [Validators.required]),
    reference: ['', [Validators.maxLength(200)]],
  });

  protected toggle(id: string): void {
    this.picked.update((s) => {
      const next = new Set(s);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }

  protected send(): void {
    const v = this.form.getRawValue();
    if (!this.picked().size || this.sending()) {
      return;
    }
    this.sending.set(true);
    this.error.set(null);
    this.api
      .send(this.data.statement.id, { channel: v.channel, reference: v.reference.trim() || null, exportIds: [...this.picked()] })
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({ next: (s) => this.ref.close(s), error: (err: unknown) => this.error.set(errorText(err)) });
  }
}

/** Último arquivo gerado de cada formato. */
export function latestByFormat(exports: readonly ExportView[]): ExportView[] {
  const map = new Map<string, ExportView>();
  for (const x of [...exports].sort((a, b) => a.createdAt.localeCompare(b.createdAt))) {
    map.set(x.format, x);
  }
  return [...map.values()];
}

export interface AdjustmentDialogData {
  statement: StatementView;
  /** Demonstrativos aprovados anteriores do credor (correção de período anterior). */
  previous: readonly StatementView[];
}

/**
 * Ajuste com motivo obrigatório (10 a 2000 caracteres) e efeito com sinal; acima do limite fica
 * aguardando a segunda aprovação de outra pessoa. Ajuste aplicado devolve o demonstrativo a calculado.
 */
@Component({
  selector: 'app-adjustment-dialog',
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule, Amount],
  template: `
    <h2 mat-dialog-title>Pedir ajuste no demonstrativo</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        <div class="app-form-grid">
          <mat-form-field>
            <mat-label>Categoria</mat-label>
            <mat-select formControlName="category" required>
              @for (c of categories; track c[0]) {
                <mat-option [value]="c[0]">{{ c[1] }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Efeito no repasse (R$)</mat-label>
            <input matInput formControlName="amount" inputmode="decimal" required />
            <mat-hint>Use − para reduzir o valor a repassar ao credor</mat-hint>
            <mat-error>Informe um valor diferente de zero.</mat-error>
          </mat-form-field>
          @if (value().category === 'PRIOR_PERIOD_CORRECTION') {
            <mat-form-field class="app-span-all">
              <mat-label>Demonstrativo corrigido</mat-label>
              <mat-select formControlName="related">
                @for (s of data.previous; track s.id) {
                  <mat-option [value]="s.id">nº {{ s.sequenceNumber }} · {{ s.periodStart.split('-').reverse().join('/') }} a {{ s.periodEnd.split('-').reverse().join('/') }}</mat-option>
                }
              </mat-select>
            </mat-form-field>
          }
          <mat-form-field class="app-span-all">
            <mat-label>Motivo</mat-label>
            <textarea matInput formControlName="reason" rows="3" maxlength="2000" required></textarea>
            <mat-hint>{{ value().reason.trim().length }}/2000 · mínimo {{ minReason }} caracteres; vai para o arquivo do credor</mat-hint>
            <mat-error>Descreva o motivo (mínimo {{ minReason }} caracteres).</mat-error>
          </mat-form-field>
        </div>
        @if (amount() !== null) {
          <p class="app-dialog-message">Efeito: <app-amount [value]="amount()" [signed]="true" /> no líquido a repassar.</p>
        }
        @if (dual()) {
          <p class="app-alert app-alert--warning" role="note" data-tour="adjustment-dual">
            <mat-icon aria-hidden="true">group</mat-icon>
            <span>Acima de {{ threshold }}, o ajuste fica <strong>aguardando a segunda aprovação de outra pessoa</strong> com permissão de aprovação. Você não poderá aprová-lo, nem aprovar o demonstrativo depois de pedir ajuste.</span>
          </p>
        } @else {
          <p class="app-alert app-alert--neutral" role="note">
            <mat-icon aria-hidden="true">info</mat-icon>
            <span>O ajuste é aplicado na hora e o demonstrativo volta a "calculado" (precisa de nova revisão). Quem pede ajuste não aprova o demonstrativo.</span>
          </p>
        }
        @if (error()) {
          <p class="app-alert app-alert--error" role="alert"><mat-icon aria-hidden="true">error_outline</mat-icon><span>{{ error() }}</span></p>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="sending()">{{ dual() ? 'Pedir segunda aprovação' : 'Aplicar ajuste' }}</button>
      </mat-dialog-actions>
    </form>
  `,
})
export class AdjustmentDialog {
  protected readonly data = inject<AdjustmentDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<AdjustmentDialog, AdjustmentView>>(MatDialogRef);
  private readonly api = inject(StatementsApi);
  protected readonly categories = Object.entries(ADJUSTMENT_CATEGORY_LABELS) as [AdjustmentCategory, string][];
  protected readonly minReason = ADJUSTMENT_REASON_MIN;
  protected readonly threshold = formatAmount(ADJUSTMENT_DUAL_APPROVAL_THRESHOLD);
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    category: this.fb.control<AdjustmentCategory>('COMMISSION_CORRECTION', [Validators.required]),
    amount: ['', [AppValidators.notBlank, (c) => (signedMoney(c.value) === null || signedMoney(c.value) === 0 ? { money: true } : null)]],
    reason: ['', [Validators.required, (c) => ((c.value as string).trim().length < ADJUSTMENT_REASON_MIN ? { minlength: true } : null)]],
    related: [''],
  });
  private readonly values = toSignal(this.form.valueChanges.pipe(startWith(null)), { initialValue: null });
  protected readonly value = computed(() => {
    this.values();
    return this.form.getRawValue();
  });
  protected readonly amount = computed(() => signedMoney(this.value().amount));
  protected readonly dual = computed(() => needsSecondApproval(this.amount()));

  protected save(): void {
    const v = this.form.getRawValue();
    if (this.form.invalid || this.sending()) {
      this.form.markAllAsTouched();
      return;
    }
    this.sending.set(true);
    this.error.set(null);
    this.api
      .requestAdjustment(this.data.statement.id, {
        category: v.category,
        amount: signedMoney(v.amount)!,
        reason: v.reason.trim(),
        relatedStatementId: v.category === 'PRIOR_PERIOD_CORRECTION' ? v.related || null : null,
      })
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({ next: (a) => this.ref.close(a), error: (err: unknown) => this.error.set(errorText(err)) });
  }
}

/** Valor com sinal ("-150,00", "−150,00" ou "150,00"). */
export function signedMoney(text: string | null | undefined): number | null {
  const raw = (text ?? '').trim();
  const negative = /^[-−]/.test(raw);
  const value = parseMoney(raw.replace(/^[-−+]\s*/, ''));
  return value === null ? null : negative ? -value : value;
}
