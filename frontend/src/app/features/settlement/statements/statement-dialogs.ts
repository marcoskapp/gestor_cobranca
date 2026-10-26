import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize, startWith } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { AppDatePipe } from '../../../shared/pipes/date-time.pipe';
import { StatementsApi } from '../data/settlement.api';
import { ADJUSTMENT_CATEGORY_LABELS, EXPORT_FORMAT_LABELS, SEND_CHANNEL_LABELS, SETTLEMENT_ERROR_LABELS } from '../data/settlement.labels';
import { AdjustmentCategory, AdjustmentView, ExportView, SendChannel, StatementView } from '../data/settlement.models';
import { ADJUSTMENT_DUAL_APPROVAL_THRESHOLD, ADJUSTMENT_REASON_MIN, needsSecondApproval } from '../data/settlement.rules';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';

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
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, AppDatePipe, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Registrar envio ao credor</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="send()" novalidate>
      <div class="modal-body">
        <p class="app-dialog-message">O envio é feito fora do sistema (e-mail, portal do credor…). Registre aqui o canal, a referência e quais arquivos foram enviados.</p>
        <app-field class="mb-3" label="Canal">
          <select appInput ngbAutofocus formControlName="channel" required>
            @for (c of channels; track c[0]) {
              <option [ngValue]="c[0]">{{ c[1] }}</option>
            }
          </select>
        </app-field>
        <app-field class="mb-3" label="Referência (opcional)" hint="Ex.: protocolo do e-mail ou número do chamado no credor">
          <input appInput formControlName="reference" maxlength="200" />
        </app-field>
        <fieldset class="sd-files">
          <legend class="form-label fs-5">Arquivos enviados</legend>
          @for (x of data.exports; track x.id) {
            <div class="form-check mb-0">
              <input class="form-check-input" type="checkbox" [id]="'sd-file-' + x.id" [checked]="picked().has(x.id)" (change)="toggle(x.id)" />
              <label class="form-check-label" [for]="'sd-file-' + x.id">
                {{ formats[x.format] }} · {{ x.fileName }}
                <span class="app-muted sd-small">gerado em {{ x.createdAt | appDate }}</span>
              </label>
            </div>
          }
        </fieldset>
        @if (error()) {
          <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="!picked().size || form.invalid || sending()">Registrar envio</button>
      </div>
    </form>
  `,
  styles: `
    .sd-files {
      display: grid;
      gap: 4px;
      margin: 0;
      padding: 8px 12px;
      border: 1px solid var(--bs-border-color);
      border-radius: var(--bs-border-radius);
    }
    .sd-small {
      margin-left: 6px;
      font-size: var(--app-text-xs);
    }
  `,
})
export class SendDialog {
  protected readonly data = inject<SendDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<StatementView>>(DialogRef);
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
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Amount, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Pedir ajuste no demonstrativo</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <div class="app-form-grid">
          <app-field label="Categoria">
            <select appInput ngbAutofocus formControlName="category" required>
              @for (c of categories; track c[0]) {
                <option [ngValue]="c[0]">{{ c[1] }}</option>
              }
            </select>
          </app-field>
          <app-field label="Efeito no repasse" hint="Digite − ou use +/− para reduzir o valor a repassar ao credor" error="Informe um valor diferente de zero.">
            <div class="input-group">
              <span class="input-group-text" aria-hidden="true">R$</span>
              <input appInput appMoney #sign="appMoney" allowNegative formControlName="amount" required />
              <button class="btn btn-outline-secondary" type="button" (click)="sign.toggleSign()" [attr.aria-pressed]="sign.negative" aria-label="Valor negativo (reduz o repasse)">+/−</button>
            </div>
          </app-field>
          @if (value().category === 'PRIOR_PERIOD_CORRECTION') {
            <app-field class="app-span-all" label="Demonstrativo corrigido">
              <select appInput formControlName="related">
                @for (s of data.previous; track s.id) {
                  <option [ngValue]="s.id">nº {{ s.sequenceNumber }} · {{ s.periodStart.split('-').reverse().join('/') }} a {{ s.periodEnd.split('-').reverse().join('/') }}</option>
                }
              </select>
            </app-field>
          }
          <app-field class="app-span-all" label="Motivo" [hint]="value().reason.trim().length + '/2000 · mínimo ' + minReason + ' caracteres; vai para o arquivo do credor'" [error]="'Descreva o motivo (mínimo ' + minReason + ' caracteres).'">
            <textarea appInput formControlName="reason" rows="3" maxlength="2000" required></textarea>
          </app-field>
        </div>
        @if (amount() !== null) {
          <p class="app-dialog-message">Efeito: <app-amount [value]="amount()" [signed]="true" /> no líquido a repassar.</p>
        }
        @if (dual()) {
          <div class="alert alert-warning d-flex align-items-start gap-2" role="note" data-tour="adjustment-dual">
            <app-icon name="people" />
            <span>Acima de {{ threshold }}, o ajuste fica <strong>aguardando a segunda aprovação de outra pessoa</strong> com permissão de aprovação. Você não poderá aprová-lo, nem aprovar o demonstrativo depois de pedir ajuste.</span>
          </div>
        } @else {
          <div class="alert alert-secondary d-flex align-items-start gap-2" role="note">
            <app-icon name="info-circle" />
            <span>O ajuste é aplicado na hora e o demonstrativo volta a "calculado" (precisa de nova revisão). Quem pede ajuste não aprova o demonstrativo.</span>
          </div>
        }
        @if (error()) {
          <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="sending()">{{ dual() ? 'Pedir segunda aprovação' : 'Aplicar ajuste' }}</button>
      </div>
    </form>
  `,
})
export class AdjustmentDialog {
  protected readonly data = inject<AdjustmentDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<AdjustmentView>>(DialogRef);
  private readonly api = inject(StatementsApi);
  protected readonly categories = Object.entries(ADJUSTMENT_CATEGORY_LABELS) as [AdjustmentCategory, string][];
  protected readonly minReason = ADJUSTMENT_REASON_MIN;
  protected readonly threshold = formatAmount(ADJUSTMENT_DUAL_APPROVAL_THRESHOLD);
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    category: this.fb.control<AdjustmentCategory>('COMMISSION_CORRECTION', [Validators.required]),
    amount: this.fb.control<number | null>(null, [Validators.required, (c) => (c.value === 0 ? { money: true } : null)]),
    reason: ['', [Validators.required, (c) => ((c.value as string).trim().length < ADJUSTMENT_REASON_MIN ? { minlength: true } : null)]],
    related: [''],
  });
  private readonly values = toSignal(this.form.valueChanges.pipe(startWith(null)), { initialValue: null });
  protected readonly value = computed(() => {
    this.values();
    return this.form.getRawValue();
  });
  protected readonly amount = computed(() => this.value().amount);
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
        amount: v.amount!,
        reason: v.reason.trim(),
        relatedStatementId: v.category === 'PRIOR_PERIOD_CORRECTION' ? v.related || null : null,
      })
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({ next: (a) => this.ref.close(a), error: (err: unknown) => this.error.set(errorText(err)) });
  }
}
