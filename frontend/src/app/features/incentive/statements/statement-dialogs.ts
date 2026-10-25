import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { AbstractControl, FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { finalize, startWith } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { Icon } from '../../../shared/components/icon/icon';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';
import { FORM_FIELD } from '../../../shared/forms/field';
import { CommissionStatementsApi } from '../data/incentive.api';
import { COMPONENT_LABELS, INCENTIVE_ERROR_LABELS } from '../data/incentive.labels';
import { AdjustmentView, DisputeView, LedgerEntryView, StatementLine } from '../data/incentive.models';
import { DUAL_APPROVAL_THRESHOLD, REASON_MAX, REASON_MIN, needsSecondApproval, signedMoney } from '../data/incentive.rules';

export const incentiveError = (err: unknown) => {
  const e = toApiError(err);
  return (e.code && INCENTIVE_ERROR_LABELS[e.code]) || userMessage(e);
};

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const minText = (min: number) => (c: { value: unknown }) => (String(c.value ?? '').trim().length < min ? { minlength: { requiredLength: min } } : null);
const MONEY_ERROR = 'Informe um valor diferente de zero, com até 2 casas (use − para reduzir).';

export interface DisputeDialogData {
  statementId: string;
  lines: readonly StatementLine[];
  /** Alvo já escolhido na tela (linha ou lançamento); sem alvo, a pessoa escolhe. */
  line?: StatementLine;
  entry?: LedgerEntryView;
}

type DisputeTarget = 'LINE' | 'ENTRY' | 'AGREEMENT';

/** Contestação item a item pelo próprio negociador (linha da memória, lançamento ou acordo faltante). */
@Component({
  selector: 'app-incentive-dispute-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Amount, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Contestar item do extrato</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <p class="app-dialog-message">A gestão analisa a contestação e pode aceitá-la com ajuste. Enquanto houver contestação em aberto, o extrato não é aprovado.</p>
        @if (data.entry; as e) {
          <dl class="app-definition-list mb-3">
            <dt>Lançamento</dt>
            <dd>Pagamento de {{ e.paidOn?.split('-')?.reverse()?.join('/') }} · creditado <app-amount [value]="e.creditedAmount" /></dd>
          </dl>
        } @else {
          <fieldset class="mb-3">
            <legend class="form-label">O que você contesta</legend>
            <div class="form-check">
              <input class="form-check-input" type="radio" id="dd-line" value="LINE" formControlName="target" [attr.disabled]="data.lines.length ? null : true" />
              <label class="form-check-label" for="dd-line">Uma linha da memória de cálculo</label>
            </div>
            <div class="form-check">
              <input class="form-check-input" type="radio" id="dd-agreement" value="AGREEMENT" formControlName="target" />
              <label class="form-check-label" for="dd-agreement">Um acordo meu que não aparece no extrato</label>
            </div>
          </fieldset>
          @if (value().target === 'LINE') {
            <app-field class="mb-3" label="Linha">
              <select appInput formControlName="line">
                @for (l of data.lines; track l.number) {
                  <option [ngValue]="l.number">{{ l.number }} · {{ component(l.component) }} · {{ amount(l.amount) }}</option>
                }
              </select>
            </app-field>
          } @else {
            <app-field class="mb-3" label="Identificador do acordo" hint="Copie da tela do acordo (Negociação › Acordos)." error="Informe o identificador completo do acordo.">
              <input appInput formControlName="agreementId" autocomplete="off" spellcheck="false" class="font-monospace" />
            </app-field>
          }
        }
        <app-field label="Justificativa" [hint]="value().reason.trim().length + '/' + max + ' · mínimo ' + min + ' caracteres'" [error]="'Explique a contestação (mínimo ' + min + ' caracteres).'">
          <textarea appInput ngbAutofocus formControlName="reason" rows="4" [attr.maxlength]="max"></textarea>
        </app-field>
        @if (error()) {
          <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="sending()">Enviar contestação</button>
      </div>
    </form>
  `,
})
export class DisputeDialog {
  protected readonly data = inject<DisputeDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<DisputeView>>(DialogRef);
  private readonly api = inject(CommissionStatementsApi);
  protected readonly min = REASON_MIN;
  protected readonly max = REASON_MAX;
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    target: this.fb.control<DisputeTarget>(this.data.entry ? 'ENTRY' : this.data.lines.length ? 'LINE' : 'AGREEMENT'),
    line: this.fb.control<number>(this.data.line?.number ?? this.data.lines[0]?.number ?? 1),
    agreementId: [''],
    reason: ['', [Validators.required, Validators.maxLength(REASON_MAX), minText(REASON_MIN)]],
  });
  private readonly values = toSignal(this.form.valueChanges.pipe(startWith(null)), { initialValue: null });
  protected readonly value = computed(() => {
    this.values();
    return this.form.getRawValue();
  });

  protected component(code: string): string {
    return COMPONENT_LABELS[code] ?? code;
  }

  protected amount(value: number): string {
    return formatAmount(value);
  }

  protected save(): void {
    const v = this.form.getRawValue();
    const agreementInvalid = v.target === 'AGREEMENT' && !UUID.test(v.agreementId.trim());
    this.form.controls.agreementId.setErrors(agreementInvalid ? { pattern: true } : null);
    if (this.form.invalid || agreementInvalid || this.sending()) {
      this.form.markAllAsTouched();
      return;
    }
    this.sending.set(true);
    this.error.set(null);
    this.api
      .dispute(this.data.statementId, {
        lineNumber: v.target === 'LINE' ? v.line : null,
        ledgerEntryId: v.target === 'ENTRY' ? (this.data.entry?.id ?? null) : null,
        agreementId: v.target === 'AGREEMENT' ? v.agreementId.trim() : v.target === 'ENTRY' ? (this.data.entry?.agreementId ?? null) : null,
        reason: v.reason.trim(),
      })
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({ next: (d) => this.ref.close(d), error: (err: unknown) => this.error.set(incentiveError(err)) });
  }
}

export interface ResolveDialogData {
  statementId: string;
  dispute: DisputeView;
  target: string;
  openedBy: string;
}

/** Resolução da contestação pela gestão: aceitar (com ajuste opcional) ou recusar, sempre com resposta. */
@Component({
  selector: 'app-incentive-resolve-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Amount, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Resolver contestação</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <dl class="app-definition-list mb-3">
          <dt>Item</dt>
          <dd>{{ data.target }}</dd>
          <dt>Aberta por</dt>
          <dd>{{ data.openedBy }}</dd>
          <dt>Justificativa</dt>
          <dd>{{ data.dispute.reason }}</dd>
        </dl>
        <fieldset class="mb-3">
          <legend class="form-label">Decisão</legend>
          <div class="form-check">
            <input class="form-check-input" type="radio" id="rd-accept" [value]="true" formControlName="accepted" />
            <label class="form-check-label" for="rd-accept">Aceitar (procedente)</label>
          </div>
          <div class="form-check">
            <input class="form-check-input" type="radio" id="rd-reject" [value]="false" formControlName="accepted" />
            <label class="form-check-label" for="rd-reject">Recusar (improcedente)</label>
          </div>
        </fieldset>
        @if (value().accepted) {
          <app-field class="mb-3" label="Ajuste no extrato (R$, opcional)" hint="Use − para reduzir. Deixe vazio para aceitar sem ajuste (ex.: depois de recalcular)." [error]="moneyError">
            <input appInput formControlName="amount" inputmode="decimal" autocomplete="off" />
          </app-field>
          @if (amount() !== null) {
            <p class="app-dialog-message">Efeito: <app-amount [value]="amount()" [signed]="true" /> no valor a pagar.</p>
          }
          @if (dual()) {
            <div class="alert alert-warning d-flex align-items-start gap-2" role="note">
              <app-icon name="people" />
              <span>Acima de {{ threshold }}, o ajuste fica aguardando a segunda aprovação de outra pessoa da gestão.</span>
            </div>
          }
        }
        <app-field label="Resposta ao negociador" [hint]="value().resolution.trim().length + '/' + max + ' · mínimo ' + min + ' caracteres'" [error]="'Escreva a resposta (mínimo ' + min + ' caracteres).'">
          <textarea appInput ngbAutofocus formControlName="resolution" rows="3" [attr.maxlength]="max"></textarea>
        </app-field>
        @if (error()) {
          <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="sending()">{{ value().accepted ? 'Aceitar contestação' : 'Recusar contestação' }}</button>
      </div>
    </form>
  `,
})
export class ResolveDialog {
  protected readonly data = inject<ResolveDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<DisputeView>>(DialogRef);
  private readonly api = inject(CommissionStatementsApi);
  protected readonly min = REASON_MIN;
  protected readonly max = REASON_MAX;
  protected readonly threshold = formatAmount(DUAL_APPROVAL_THRESHOLD);
  protected readonly moneyError = MONEY_ERROR;
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    accepted: [true],
    amount: ['', [(c: AbstractControl) => (String(c.value ?? '').trim() && !signedMoney(c.value as string) ? { money: true } : null)]],
    resolution: ['', [Validators.required, Validators.maxLength(REASON_MAX), minText(REASON_MIN)]],
  });
  private readonly values = toSignal(this.form.valueChanges.pipe(startWith(null)), { initialValue: null });
  protected readonly value = computed(() => {
    this.values();
    return this.form.getRawValue();
  });
  protected readonly amount = computed(() => (this.value().accepted ? signedMoney(this.value().amount) : null));
  protected readonly dual = computed(() => needsSecondApproval(this.amount()));

  protected save(): void {
    const v = this.form.getRawValue();
    if (!v.accepted) {
      this.form.controls.amount.setValue('');
    }
    if (this.form.invalid || this.sending()) {
      this.form.markAllAsTouched();
      return;
    }
    this.sending.set(true);
    this.error.set(null);
    this.api
      .resolve(this.data.statementId, this.data.dispute.id, { accepted: v.accepted, resolution: v.resolution.trim(), adjustmentAmount: v.accepted ? signedMoney(v.amount) : null })
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({ next: (d) => this.ref.close(d), error: (err: unknown) => this.error.set(incentiveError(err)) });
  }
}

export interface AdjustDialogData {
  statementId: string;
  beneficiary: string;
  period: string;
  payable: number;
}

/** Ajuste manual com motivo; acima do limite fica aguardando a segunda aprovação de outra pessoa. */
@Component({
  selector: 'app-incentive-adjust-dialog',
  imports: [ReactiveFormsModule, ...DIALOG_DIRECTIVES, ...FORM_FIELD, Amount, Icon],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">Ajustar extrato</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form class="d-flex flex-column overflow-hidden" [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <dl class="app-definition-list mb-3">
          <dt>Negociador</dt>
          <dd>{{ data.beneficiary }}</dd>
          <dt>Período</dt>
          <dd>{{ data.period }}</dd>
          <dt>A pagar hoje</dt>
          <dd><app-amount [value]="data.payable" /></dd>
        </dl>
        <div class="app-form-grid">
          <app-field label="Valor do ajuste (R$)" hint="Use − para reduzir o valor a pagar" [error]="moneyError">
            <input appInput ngbAutofocus formControlName="amount" inputmode="decimal" autocomplete="off" />
          </app-field>
          <app-field class="app-span-all" label="Motivo" [hint]="value().reason.trim().length + '/' + max + ' · mínimo ' + min + ' caracteres; fica na trilha de auditoria'" [error]="'Descreva o motivo (mínimo ' + min + ' caracteres).'">
            <textarea appInput formControlName="reason" rows="3" [attr.maxlength]="max"></textarea>
          </app-field>
        </div>
        @if (amount() !== null) {
          <p class="app-dialog-message">Efeito: <app-amount [value]="amount()" [signed]="true" /> no valor a pagar.</p>
        }
        @if (dual()) {
          <div class="alert alert-warning d-flex align-items-start gap-2" role="note">
            <app-icon name="people" />
            <span>Acima de {{ threshold }}, o ajuste fica <strong>aguardando a segunda aprovação de outra pessoa</strong> da gestão. Quem pede ajuste não aprova o extrato.</span>
          </div>
        } @else {
          <div class="alert alert-secondary d-flex align-items-start gap-2" role="note">
            <app-icon name="info-circle" />
            <span>O ajuste é aplicado na hora. Quem pede ajuste não aprova o extrato (segregação de funções).</span>
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
export class AdjustDialog {
  protected readonly data = inject<AdjustDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<AdjustmentView>>(DialogRef);
  private readonly api = inject(CommissionStatementsApi);
  protected readonly min = REASON_MIN;
  protected readonly max = REASON_MAX;
  protected readonly threshold = formatAmount(DUAL_APPROVAL_THRESHOLD);
  protected readonly moneyError = MONEY_ERROR;
  protected readonly sending = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    amount: ['', [(c: AbstractControl) => (!signedMoney(c.value as string) ? { money: true } : null)]],
    reason: ['', [Validators.required, Validators.maxLength(REASON_MAX), minText(REASON_MIN)]],
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
      .adjust(this.data.statementId, signedMoney(v.amount)!, v.reason.trim())
      .pipe(finalize(() => this.sending.set(false)))
      .subscribe({ next: (a) => this.ref.close(a), error: (err: unknown) => this.error.set(incentiveError(err)) });
  }
}
