import { Component, computed, inject, input, output, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { finalize, of } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { ImportReviewsApi } from '../data/ingestion.api';
import { BreakdownRow, MeasureView, ReviewView, TotalsCheckView, TotalsMeasure, TotalsRequest } from '../data/ingestion.models';
import { BREAKDOWN_LABELS, DIMENSION_LABELS, MEASURE_LABELS, REVIEW_ERROR_LABELS, parseBrazilianAmount, parseCount, scaleText } from '../data/review.rules';
import { Icon } from '../../../shared/components/icon/icon';
import { FORM_FIELD } from '../../../shared/forms/field';

type Field = 'rows' | 'principal' | 'balance' | 'contract';
const FIELD_MEASURE: Record<Field, TotalsMeasure> = { rows: 'ROWS', principal: 'PRINCIPAL', balance: 'BALANCE', contract: 'CONTRACT_AMOUNT' };

/** Pedido de conferência a partir do formulário (valores no padrão brasileiro); `null` se algo estiver inválido. */
export function totalsRequest(v: Record<Field, { use: boolean; value: string }>): TotalsRequest | null {
  const request: TotalsRequest = { rows: null, principalTotal: null, balanceTotal: null, contractAmountTotal: null };
  let any = false;
  for (const field of Object.keys(FIELD_MEASURE) as Field[]) {
    if (!v[field].use) continue;
    const n = field === 'rows' ? parseCount(v[field].value) : parseBrazilianAmount(v[field].value);
    if (n === null) return null;
    any = true;
    if (field === 'rows') request.rows = n;
    if (field === 'principal') request.principalTotal = n;
    if (field === 'balance') request.balanceTotal = n;
    if (field === 'contract') request.contractAmountTotal = n;
  }
  return any ? request : null;
}

/**
 * Passo 4: o usuário informa os totais que tem na planilha (quantidade e somas) e o sistema compara com o arquivo já
 * convertido. Os totais calculados só aparecem depois da primeira conferência, para que o número venha da planilha.
 */
@Component({
  selector: 'app-totals-step',
  imports: [...FORM_FIELD, Icon, ReactiveFormsModule, Amount],
  template: `
    @if (review().batch.status !== 'VALIDATED') {
      <p class="app-muted" role="status">Os totais ficam disponíveis quando a validação das linhas terminar.</p>
    } @else {
      <p class="ts-intro">
        <strong>Confira os totais com a sua planilha.</strong> Some as colunas no Excel (por exemplo, <span class="app-mono">=SOMA(J2:J5000)</span>) e conte as linhas de dados,
        sem o cabeçalho. Escolha os totais que você tem e digite-os como aparecem na planilha.
      </p>
      @if (canEdit()) {
        <form class="ts-form" [formGroup]="form" (ngSubmit)="check()" novalidate data-tour="review-totals">
          @for (f of fields; track f.key) {
            @let group = form.controls[f.key];
            <div class="ts-field" [formGroup]="group">
              <div class="form-check">
                <input class="form-check-input" type="checkbox" id="totals-use" formControlName="use" />
                <label class="form-check-label" for="totals-use">{{ f.label }}</label>
              </div>
              @if (group.controls.use.value) {
                <app-field class="ts-input" [label]="(f.key === 'rows' ? 'Linhas na planilha' : 'Total na planilha (R$)')" [hint]="(preview(f.key))">
                  <input appInput [formControl]="group.controls.value" [attr.inputmode]="f.key === 'rows' ? 'numeric' : 'decimal'" autocomplete="off" [placeholder]="f.key === 'rows' ? '1.250' : '1.234.567,89'" />
                </app-field>
              }
            </div>
          }
          @if (error()) {
            <div class="alert alert-danger d-flex align-items-start gap-2" role="alert"><app-icon name="exclamation-circle" /><span>{{ error() }}</span></div>
          }
          <div class="ts-actions">
            <button class="btn btn-primary" type="submit" [disabled]="saving() || !request()"><app-icon name="card-checklist" />Conferir</button>
          </div>
        </form>
      }
      @if (result(); as r) {
        <section class="app-card ts-result" aria-labelledby="ts-result" aria-live="polite">
          <h3 class="ts-title" id="ts-result">
            @if (r.matched) {
              <span class="app-status app-status--success"><app-icon name="check2-circle" />Os totais conferem</span>
            } @else {
              <span class="app-status app-status--danger"><app-icon name="exclamation-circle" />Os totais não conferem: a importação fica bloqueada</span>
            }
          </h3>
          <div class="ts-table-wrap">
            <table class="ts-table">
              <caption class="visually-hidden">Totais informados e calculados</caption>
              <thead>
                <tr><th scope="col">Total</th><th scope="col" class="num">Sua planilha</th><th scope="col" class="num">Arquivo convertido</th><th scope="col" class="num">Diferença</th><th scope="col">Situação</th></tr>
              </thead>
              <tbody>
                @for (m of r.measures; track m.measure) {
                  <tr>
                    <th scope="row">{{ measureLabel(m) }}</th>
                    <td class="num">{{ value(m, m.entered) }}</td>
                    <td class="num">{{ value(m, m.computed) }}@if (m.invalidCount) {<div class="ts-note">{{ m.invalidCount }} valor(es) não lidos</div>}</td>
                    <td class="num">{{ value(m, m.difference, true) }}</td>
                    <td>
                      @if (m.matches) {
                        <span class="app-status app-status--success"><app-icon name="check-lg" />Confere</span>
                      } @else {
                        <span class="app-status app-status--danger"><app-icon name="x-lg" />Diverge</span>
                        @if (m.scaleExponent !== null) {
                          <div class="ts-note">{{ scale(m.scaleExponent) }}</div>
                        }
                      }
                    </td>
                  </tr>
                }
              </tbody>
            </table>
          </div>
          @for (h of r.hints; track h.code + h.measure) {
            @if (h.code !== 'MATCHED') {
              <div class="alert d-flex align-items-start gap-2" [class.alert-danger]="h.code === 'SCALE'" [class.alert-warning]="h.code !== 'SCALE'" [attr.role]="h.code === 'SCALE' ? 'alert' : 'note'">
                <app-icon [name]="h.code === 'SCALE' ? 'exclamation-triangle' : h.code === 'ALTERNATIVE_MATCH' ? 'arrow-left-right' : 'info-circle'" />
                <span>{{ h.message }}</span>
              </div>
            }
          }
          @if (needsFormat(r)) {
            <button class="btn btn-outline-primary" type="button" (click)="goTo.emit(2)"><app-icon name="arrow-left" />Rever o formato dos valores</button>
          }
          @if (!r.matched && r.suspectRows.length) {
            <h4 class="ts-sub">Linhas para conferir primeiro</h4>
            <ul class="ts-rows">
              @for (s of r.suspectRows; track s.rowNumber) {
                <li>
                  <span class="app-numeric">Linha {{ s.rowNumber }}</span>
                  @if (s.externalId) {<span class="app-mono">{{ s.externalId }}</span>}
                  <app-amount [value]="s.amount" />
                  @if (s.ambiguous) {<span class="app-status app-status--warning">valor ambíguo</span>}
                </li>
              }
            </ul>
          }
        </section>
      }
      @if (breakdown().length) {
        <section class="app-card" aria-labelledby="ts-breakdown">
          <h3 class="ts-title" id="ts-breakdown">Totais do arquivo por grupo</h3>
          <p class="app-muted ts-small">Ajudam a localizar a diferença: compare com os subtotais da sua planilha.</p>
          <div class="ts-groups">
            @for (d of dimensions(); track d.dimension) {
              <div class="ts-table-wrap">
                <table class="ts-table">
                  <caption>{{ d.label }}</caption>
                  <thead><tr><th scope="col">Grupo</th><th scope="col" class="num">Linhas</th><th scope="col" class="num">Principal</th><th scope="col" class="num">Saldo</th></tr></thead>
                  <tbody>
                    @for (row of d.rows; track row.key) {
                      <tr>
                        <th scope="row">{{ keyLabel(row) }}</th>
                        <td class="num">{{ row.rows }}</td>
                        <td class="num"><app-amount [value]="row.principal" /></td>
                        <td class="num"><app-amount [value]="row.balance" /></td>
                      </tr>
                    }
                  </tbody>
                </table>
              </div>
            }
          </div>
        </section>
      }
    }
  `,
  styles: `
    .ts-intro {
      margin-top: 0;
      max-width: 75ch;
    }
    .ts-form {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 260px), 1fr));
      gap: 8px 16px;
      align-items: start;
      margin-bottom: var(--app-gap);
    }
    .ts-field {
      display: flex;
      flex-direction: column;
      gap: 4px;
    }
    .ts-input {
      width: 100%;
    }
    .ts-actions {
      grid-column: 1 / -1;
      display: flex;
      justify-content: flex-end;
    }
    .ts-result {
      margin-bottom: var(--app-gap);
    }
    .ts-title {
      margin: 0 0 8px;
      font-size: var(--app-text-md);
      font-weight: 600;
    }
    .ts-sub {
      margin: 12px 0 4px;
      font-size: var(--app-text-sm);
      font-weight: 600;
    }
    .ts-table-wrap {
      overflow-x: auto;
      margin-bottom: 12px;
    }
    .ts-table {
      width: 100%;
      border-collapse: collapse;
      font-size: var(--app-text-sm);
    }
    .ts-table caption {
      text-align: left;
      font-weight: 600;
      padding-bottom: 4px;
    }
    .ts-table th,
    .ts-table td {
      padding: 4px 8px;
      text-align: left;
      border-bottom: 1px solid var(--bs-border-color);
      vertical-align: top;
    }
    .ts-table thead th {
      font-weight: 600;
      color: var(--bs-secondary-color);
    }
    .ts-table .num {
      text-align: right;
      font-variant-numeric: tabular-nums;
      white-space: nowrap;
    }
    .ts-note {
      font-size: var(--app-text-xs);
      color: var(--bs-secondary-color);
    }
    .ts-rows {
      display: grid;
      gap: 4px;
      margin: 0;
      padding: 0;
      list-style: none;
      font-size: var(--app-text-sm);
    }
    .ts-rows li {
      display: flex;
      flex-wrap: wrap;
      gap: 4px 12px;
      align-items: center;
    }
    .ts-groups {
      display: grid;
      grid-template-columns: repeat(auto-fit, minmax(min(100%, 340px), 1fr));
      gap: var(--app-gap);
    }
    .ts-small {
      font-size: var(--app-text-sm);
    }
  `,
})
export class TotalsStep {
  readonly review = input.required<ReviewView>();
  readonly canExecute = input(false);
  readonly changed = output<void>();
  readonly goTo = output<number>();

  private readonly api = inject(ImportReviewsApi);
  private readonly fb = inject(FormBuilder).nonNullable;

  protected readonly fields: { key: Field; label: string }[] = [
    { key: 'rows', label: MEASURE_LABELS.ROWS },
    { key: 'principal', label: MEASURE_LABELS.PRINCIPAL },
    { key: 'balance', label: MEASURE_LABELS.BALANCE },
    { key: 'contract', label: MEASURE_LABELS.CONTRACT_AMOUNT },
  ];
  protected readonly form = this.fb.group({
    rows: this.fb.group({ use: [true], value: [''] }),
    principal: this.fb.group({ use: [true], value: [''] }),
    balance: this.fb.group({ use: [false], value: [''] }),
    contract: this.fb.group({ use: [false], value: [''] }),
  });
  private readonly formValue = signal(this.form.getRawValue());
  protected readonly request = computed(() => totalsRequest(this.formValue()));
  protected readonly saving = signal(false);
  protected readonly error = signal<string | null>(null);
  private readonly checked = signal<TotalsCheckView | null>(null);
  protected readonly result = computed(() => this.checked() ?? this.review().totals.lastCheck);
  protected readonly canEdit = computed(() => this.canExecute() && this.review().status === 'OPEN');

  private readonly breakdownResource = rxResource({
    params: () => (this.review().totals.checked ? { id: this.review().id, check: this.result()?.id } : undefined),
    stream: ({ params }) => (params ? this.api.breakdown(params.id) : of([])),
  });
  protected readonly breakdown = computed(() => (this.breakdownResource.hasValue() ? (this.breakdownResource.value() ?? []) : []));
  protected readonly dimensions = computed(() =>
    ['VALIDITY', 'AGING', 'SITUATION', 'PRODUCT'].map((dimension) => ({
      dimension,
      label: DIMENSION_LABELS[dimension],
      rows: this.breakdown().filter((r) => r.dimension === dimension),
    })).filter((d) => d.rows.length),
  );

  constructor() {
    this.form.valueChanges.subscribe(() => this.formValue.set(this.form.getRawValue()));
  }

  protected preview(field: Field): string {
    const raw = this.formValue()[field].value;
    if (!raw.trim()) {
      return field === 'rows' ? 'Sem o cabeçalho' : 'Use vírgula para os centavos';
    }
    const n = field === 'rows' ? parseCount(raw) : parseBrazilianAmount(raw);
    if (n === null) {
      return field === 'rows' ? 'Número inválido' : 'Valor inválido: use 1.234,56';
    }
    return field === 'rows' ? `${new Intl.NumberFormat('pt-BR').format(n)} linhas` : `Você digitou ${formatAmount(n)}`;
  }

  protected measureLabel(m: MeasureView): string {
    return MEASURE_LABELS[m.measure];
  }

  protected value(m: MeasureView, n: number, signed = false): string {
    if (m.measure === 'ROWS') {
      const text = new Intl.NumberFormat('pt-BR').format(n);
      return signed && n > 0 ? `+${text}` : text;
    }
    const text = formatAmount(n);
    return signed && n > 0 ? `+${text}` : text;
  }

  protected scale(exponent: number): string {
    return scaleText(exponent);
  }

  protected needsFormat(r: TotalsCheckView): boolean {
    return !r.matched && r.hints.some((h) => h.code === 'SCALE' || h.code === 'ALTERNATIVE_MATCH');
  }

  protected keyLabel(row: BreakdownRow): string {
    return BREAKDOWN_LABELS[row.dimension]?.[row.key] ?? row.key;
  }

  protected check(): void {
    const request = this.request();
    if (!request || this.saving()) {
      return;
    }
    this.saving.set(true);
    this.error.set(null);
    this.api
      .checkTotals(this.review().id, request)
      .pipe(finalize(() => this.saving.set(false)))
      .subscribe({
        next: (result) => {
          this.checked.set(result);
          this.changed.emit();
        },
        error: (err: unknown) => {
          const e = toApiError(err);
          this.error.set((e.code && REVIEW_ERROR_LABELS[e.code]) || userMessage(e));
        },
      });
  }
}
