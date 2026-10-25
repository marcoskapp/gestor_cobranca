import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { startWith } from 'rxjs';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PortfolioLookup } from '../../organization/data/organization.lookups';
import { PAYMENT_TYPE_LABELS, RECEIVED_BY_LABELS } from '../data/settlement.labels';
import { PaymentType, RateRule, ReceivedBy } from '../data/settlement.models';
import { rateRuleProblems } from '../data/settlement.rules';
import { FORM_FIELD } from '../../../shared/forms/field';
import { DIALOG_DATA, DialogRef } from '../../../shared/dialog/dialog-ref';
import { DIALOG_DIRECTIVES } from '../../../shared/dialog/dialog.directives';

export interface RateRuleDialogData {
  creditorId: string;
  rule: RateRule | null;
  /** Códigos já usados por outras regras (o código é único no conjunto). */
  usedCodes: readonly string[];
}

const toNumber = (v: string | number | null): number | null => (v === null || v === '' ? null : Number(String(v).replace(',', '.')));

/**
 * Regra específica de comissão: critérios (carteira, faixa de atraso, forma de pagamento, quem
 * recebeu, renegociação) e percentual. Critério vazio não restringe; ao menos um é exigido.
 */
@Component({
  selector: 'app-rate-rule-dialog',
  imports: [...FORM_FIELD, ReactiveFormsModule, ...DIALOG_DIRECTIVES, EntityPicker],
  template: `
    <div class="modal-header">
      <h2 appDialogTitle class="h5 mb-0">{{ data.rule ? 'Editar regra ' + data.rule.code : 'Nova regra específica' }}</h2>
      <button type="button" class="btn-close" aria-label="Cancelar" appDialogClose></button>
    </div>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <div class="modal-body">
        <div class="app-form-grid">
          <app-field label="Código" hint="Aparece na memória de cálculo e no arquivo do credor">
            <input appInput formControlName="code" maxlength="40" required (input)="upper()" />
          </app-field>
          <app-field label="Percentual (%)">
            <input appInput formControlName="rate" inputmode="decimal" required />
          </app-field>
          <app-field class="app-span-all" label="Descrição">
            <input appInput formControlName="description" maxlength="200" />
          </app-field>
          <app-field label="Prioridade" hint="Maior prioridade vence; no empate, a mais específica">
            <input appInput type="number" formControlName="priority" min="0" max="1000" />
          </app-field>
          <div class="app-span-all">
            <app-entity-picker [control]="form.controls.portfolio" [source]="portfolioSource" label="Carteira (opcional)" [single]="true" />
          </div>
          <app-field label="Atraso a partir de (dias)">
            <input appInput type="number" formControlName="minDays" min="0" />
          </app-field>
          <app-field label="Atraso até (dias)">
            <input appInput type="number" formControlName="maxDays" min="0" />
          </app-field>
          <app-field label="Forma de pagamento">
            <select appInput formControlName="paymentType">
              <option value="">Qualquer</option>
              @for (p of paymentTypes; track p[0]) {
                <option [ngValue]="p[0]">{{ p[1] }}</option>
              }
            </select>
          </app-field>
          <app-field label="Quem recebeu">
            <select appInput formControlName="receivedBy">
              <option value="">Qualquer</option>
              @for (r of receivers; track r[0]) {
                <option [ngValue]="r[0]">{{ r[1] }}</option>
              }
            </select>
          </app-field>
          <app-field label="Renegociação">
            <select appInput formControlName="renegotiation">
              <option value="">Qualquer</option>
              <option value="true">Somente renegociação</option>
              <option value="false">Somente primeiro acordo</option>
            </select>
          </app-field>
        </div>
        @if (problems().length) {
          <ul class="alert alert-warning rr-problems mb-0" role="status" aria-label="Problemas da regra">
            @for (p of problems(); track p) {
              <li>{{ p }}</li>
            }
          </ul>
        }
      </div>
      <div class="modal-footer">
        <button class="btn btn-link" type="button" appDialogClose>Cancelar</button>
        <button class="btn btn-primary" type="submit" [disabled]="problems().length > 0">Aplicar</button>
      </div>
    </form>
  `,
  styles: `
    .rr-problems {
      display: block;
      padding-left: 28px;
    }
  `,
})
export class RateRuleDialog {
  protected readonly data = inject<RateRuleDialogData>(DIALOG_DATA);
  private readonly ref = inject<DialogRef<RateRule>>(DialogRef);
  protected readonly portfolioSource = inject(PortfolioLookup).source(() => this.data.creditorId);
  protected readonly paymentTypes = Object.entries(PAYMENT_TYPE_LABELS) as [PaymentType, string][];
  protected readonly receivers = Object.entries(RECEIVED_BY_LABELS) as [ReceivedBy, string][];

  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly r = this.data.rule;
  protected readonly form = this.fb.group({
    code: [this.r?.code ?? '', [Validators.required]],
    description: [this.r?.description ?? ''],
    priority: this.fb.control<number | null>(this.r?.priority ?? 0),
    rate: this.fb.control<string>(this.r ? String(this.r.rate).replace('.', ',') : ''),
    portfolio: this.fb.control<string[]>(this.r?.portfolioId ? [this.r.portfolioId] : []),
    minDays: this.fb.control<number | null>(this.r?.minDaysPastDue ?? null),
    maxDays: this.fb.control<number | null>(this.r?.maxDaysPastDue ?? null),
    paymentType: [this.r?.paymentType ?? ''],
    receivedBy: [this.r?.receivedBy ?? ''],
    renegotiation: [this.r?.renegotiation === null || this.r?.renegotiation === undefined ? '' : String(this.r.renegotiation)],
  });
  private readonly values = toSignal(this.form.valueChanges.pipe(startWith(null)), { initialValue: null });
  protected readonly rule = computed<RateRule>(() => {
    this.values();
    const v = this.form.getRawValue();
    return {
      code: v.code.trim(),
      description: v.description.trim() || null,
      priority: v.priority ?? 0,
      portfolioId: v.portfolio[0] ?? null,
      minDaysPastDue: toNumber(v.minDays),
      maxDaysPastDue: toNumber(v.maxDays),
      paymentType: (v.paymentType || null) as PaymentType | null,
      receivedBy: (v.receivedBy || null) as ReceivedBy | null,
      renegotiation: v.renegotiation === '' ? null : v.renegotiation === 'true',
      rate: toNumber(v.rate) as number,
    };
  });
  protected readonly problems = computed(() => {
    const rule = this.rule();
    const out = rateRuleProblems(rule);
    if (rule.code && this.data.usedCodes.includes(rule.code)) {
      out.push(`Código de regra repetido: ${rule.code}.`);
    }
    return out;
  });

  protected upper(): void {
    const c = this.form.controls.code;
    c.setValue(c.value.toUpperCase().replace(/\s+/g, '_'), { emitEvent: true });
  }

  protected save(): void {
    if (this.problems().length) {
      return;
    }
    this.ref.close(this.rule());
  }
}
