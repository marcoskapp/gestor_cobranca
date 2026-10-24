import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { startWith } from 'rxjs';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PortfolioLookup } from '../../organization/data/organization.lookups';
import { PAYMENT_TYPE_LABELS, RECEIVED_BY_LABELS } from '../data/settlement.labels';
import { PaymentType, RateRule, ReceivedBy } from '../data/settlement.models';
import { rateRuleProblems } from '../data/settlement.rules';

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
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule, MatSelectModule, EntityPicker],
  template: `
    <h2 mat-dialog-title>{{ data.rule ? 'Editar regra ' + data.rule.code : 'Nova regra específica' }}</h2>
    <form [formGroup]="form" (ngSubmit)="save()" novalidate>
      <mat-dialog-content>
        <div class="app-form-grid">
          <mat-form-field>
            <mat-label>Código</mat-label>
            <input matInput formControlName="code" maxlength="40" required (input)="upper()" />
            <mat-hint>Aparece na memória de cálculo e no arquivo do credor</mat-hint>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Percentual (%)</mat-label>
            <input matInput formControlName="rate" inputmode="decimal" required />
          </mat-form-field>
          <mat-form-field class="app-span-all">
            <mat-label>Descrição</mat-label>
            <input matInput formControlName="description" maxlength="200" />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Prioridade</mat-label>
            <input matInput type="number" formControlName="priority" min="0" max="1000" />
            <mat-hint>Maior prioridade vence; no empate, a mais específica</mat-hint>
          </mat-form-field>
          <div class="app-span-all">
            <app-entity-picker [control]="form.controls.portfolio" [source]="portfolioSource" label="Carteira (opcional)" [single]="true" />
          </div>
          <mat-form-field>
            <mat-label>Atraso a partir de (dias)</mat-label>
            <input matInput type="number" formControlName="minDays" min="0" />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Atraso até (dias)</mat-label>
            <input matInput type="number" formControlName="maxDays" min="0" />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Forma de pagamento</mat-label>
            <mat-select formControlName="paymentType">
              <mat-option value="">Qualquer</mat-option>
              @for (p of paymentTypes; track p[0]) {
                <mat-option [value]="p[0]">{{ p[1] }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Quem recebeu</mat-label>
            <mat-select formControlName="receivedBy">
              <mat-option value="">Qualquer</mat-option>
              @for (r of receivers; track r[0]) {
                <mat-option [value]="r[0]">{{ r[1] }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field>
            <mat-label>Renegociação</mat-label>
            <mat-select formControlName="renegotiation">
              <mat-option value="">Qualquer</mat-option>
              <mat-option value="true">Somente renegociação</mat-option>
              <mat-option value="false">Somente primeiro acordo</mat-option>
            </mat-select>
          </mat-form-field>
        </div>
        @if (problems().length) {
          <ul class="app-alert app-alert--warning rr-problems" role="status" aria-label="Problemas da regra">
            @for (p of problems(); track p) {
              <li>{{ p }}</li>
            }
          </ul>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="problems().length > 0">Aplicar</button>
      </mat-dialog-actions>
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
  protected readonly data = inject<RateRuleDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<RateRuleDialog, RateRule>>(MatDialogRef);
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
