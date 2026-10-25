import { Component, computed, inject } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { NgbInputDatepicker } from '@ng-bootstrap/ng-bootstrap';
import { debounceTime, map, startWith } from 'rxjs';
import { toApiError, userMessage } from '../../../core/http/problem';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { Icon } from '../../../shared/components/icon/icon';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { NameMap, nameMap } from '../../../shared/data/name-map';
import { sameValue } from '../../../shared/data/same-value';
import { FORM_FIELD } from '../../../shared/forms/field';
import { isoDate, toLocalDate } from '../../../shared/models/dates';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { BarChart } from '../../analytics/components/bar-chart';
import { KpiCard } from '../../analytics/components/kpi-card';
import { CreditorLookup, CreditorNames, PortfolioNames } from '../../organization/data/organization.lookups';
import { ScoringMonitoringApi } from '../data/scoring.api';
import { GROUP_BY_LABELS, SCORING_ERROR_LABELS } from '../data/scoring.labels';
import { ExpectedRecoveryFilter, ExpectedRecoveryReport, RecoveryGroupBy } from '../data/scoring.models';
import { dec, defaultRecoveryRange, integer, percent, ratioText, recoveryRangeProblem, recoveryRows, recoveryTotals } from '../data/scoring.rules';

interface RecoveryFilterValue {
  from: string | null;
  to: string | null;
  groupBy: RecoveryGroupBy;
  creditorId: string | null;
}

const GROUPS: readonly RecoveryGroupBy[] = ['NONE', 'CREDITOR', 'PORTFOLIO', 'ASSIGNEE'];

/**
 * Recuperado × esperado das safras apuradas: valor recuperado ÷ valor que o score esperava para
 * os casos recebidos (Σ chance × saldo × fração paga típica), por credor, carteira ou
 * responsável na data do score — compara negociadores com carteiras de qualidade diferente.
 * Filtrado pelo escopo de credores do usuário.
 */
@Component({
  selector: 'app-expected-recovery-panel',
  imports: [Icon, NgbInputDatepicker, ...FORM_FIELD, ReactiveFormsModule, Amount, EmptyState, EntityPicker, Skeleton, BarChart, KpiCard],
  template: `
    <form class="app-filters" data-tour="filters" [formGroup]="filters" role="search" aria-label="Filtrar recuperado × esperado">
      <app-field label="Safras de">
        <div class="input-group">
          <input appInput ngbDatepicker #from="ngbDatepicker" formControlName="from" placeholder="dd/mm/aaaa" />
          <button type="button" class="btn btn-outline-secondary" (click)="from.toggle()" aria-label="Abrir calendário de início"><app-icon name="calendar-event" /></button>
        </div>
      </app-field>
      <app-field label="Safras até">
        <div class="input-group">
          <input appInput ngbDatepicker #to="ngbDatepicker" formControlName="to" placeholder="dd/mm/aaaa" />
          <button type="button" class="btn btn-outline-secondary" (click)="to.toggle()" aria-label="Abrir calendário de fim"><app-icon name="calendar-event" /></button>
        </div>
      </app-field>
      <app-field label="Agrupar por">
        <select appInput formControlName="groupBy">
          @for (g of groups; track g) {
            <option [ngValue]="g">{{ groupLabels[g] }}</option>
          }
        </select>
      </app-field>
      <div class="app-filters__wide">
        <app-entity-picker [control]="filters.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
    </form>

    @if (problem(); as p) {
      <div class="alert alert-warning d-flex gap-2 align-items-start" role="alert"><app-icon name="exclamation-triangle" /><span>{{ p }}</span></div>
    } @else if (report.status() === 'error') {
      <app-empty-state icon="cloud-slash" tone="error" title="Não foi possível carregar o recuperado × esperado." [message]="errorText()">
        <button class="btn btn-outline-primary" type="button" (click)="report.reload()">Tentar novamente</button>
      </app-empty-state>
    } @else if (!data()) {
      <app-skeleton variant="card" [rows]="4" />
    } @else if (!data()!.lines.length) {
      <app-empty-state
        icon="cash-stack"
        title="Nenhuma safra apurada no período."
        message="O indicador aparece quando os casos escorados num retrato completam a janela de desfecho (padrão 30 dias, mais 5 de carência)."
      />
    } @else {
      @let t = totals();
      <section class="er-kpis" aria-label="Recuperado × esperado no período" data-tour="scoring-expected-recovery" [attr.aria-busy]="report.isLoading()">
        <app-kpi-card label="Recuperado ÷ esperado" [detail]="ratioDetail()">{{ ratio(t.ratio) }}</app-kpi-card>
        <app-kpi-card label="Recuperado" detail="Pago confirmado na janela, líquido de estornos"><app-amount [value]="t.realizedAmount" [compact]="true" /></app-kpi-card>
        <app-kpi-card label="Esperado pelo score" detail="Σ chance × saldo × fração paga típica"><app-amount [value]="t.expectedAmount" [compact]="true" /></app-kpi-card>
        <app-kpi-card label="Casos que pagaram" [detail]="'esperados ' + decimalCount(t.expectedPayers) + ' de ' + int(t.cases) + ' casos'">{{ int(t.realizedPayers) }}</app-kpi-card>
      </section>
      @if (grouped()) {
        <section class="app-card">
          <app-bar-chart
            [title]="'Esperado × recuperado por ' + groupLabels[data()!.groupBy].toLowerCase()"
            [summary]="chartSummary()"
            [categoryHeader]="groupLabels[data()!.groupBy]"
            noteHeader="Recuperado ÷ esperado"
            [series]="[{ name: 'Esperado', color: '--app-chart-1' }, { name: 'Recuperado', color: '--app-chart-2' }]"
            [rows]="rows()"
          />
        </section>
      }
      <section class="app-card" aria-labelledby="er-table">
        <h2 class="app-card__title" id="er-table">Detalhe</h2>
        <div class="table-responsive">
          <table class="table table-sm align-middle mb-0">
            <thead>
              <tr>
                <th scope="col">{{ groupLabels[data()!.groupBy] }}</th>
                <th scope="col" class="text-end">Casos</th>
                <th scope="col" class="text-end">Pagantes esperados</th>
                <th scope="col" class="text-end">Pagantes</th>
                <th scope="col" class="text-end">Esperado</th>
                <th scope="col" class="text-end">Recuperado</th>
                <th scope="col" class="text-end">Recuperado ÷ esperado</th>
              </tr>
            </thead>
            <tbody>
              @for (l of data()!.lines; track l.groupKey) {
                <tr>
                  <th scope="row" class="fw-normal">{{ groupName(l.groupKey) }}</th>
                  <td class="text-end app-numeric">{{ int(l.cases) }}</td>
                  <td class="text-end app-numeric">{{ decimalCount(l.expectedPayers) }}</td>
                  <td class="text-end app-numeric">{{ int(l.realizedPayers) }}</td>
                  <td class="text-end"><app-amount [value]="l.expectedAmount" /></td>
                  <td class="text-end"><app-amount [value]="l.realizedAmount" /></td>
                  <td class="text-end app-numeric">{{ ratio(l.ratio) }}</td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <p class="er-small">Acima de 1,00×: a carteira rendeu mais do que o score previa para os casos recebidos; abaixo, menos.</p>
      </section>
    }
  `,
  styles: `
    .er-kpis {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
      gap: var(--app-gap);
      margin-bottom: var(--app-gap);
    }
    .er-small {
      margin: 8px 0 0;
      font-size: var(--app-text-sm);
      color: var(--bs-secondary-color);
    }
  `,
})
export class ExpectedRecoveryPanel {
  private readonly api = inject(ScoringMonitoringApi);
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly groups = GROUPS;
  protected readonly groupLabels = GROUP_BY_LABELS;
  protected readonly int = integer;
  protected readonly ratio = ratioText;

  private readonly fb = inject(FormBuilder).nonNullable;
  private readonly range = defaultRecoveryRange(toLocalDate(new Date())!);
  protected readonly filters = this.fb.group({
    from: this.fb.control<string | null>(this.range.from),
    to: this.fb.control<string | null>(this.range.to),
    groupBy: this.fb.control<RecoveryGroupBy>('NONE'),
    creditor: this.fb.control<string[]>([]),
  });
  private readonly value = toSignal(
    this.filters.valueChanges.pipe(
      startWith(null),
      debounceTime(250),
      map((): RecoveryFilterValue => {
        const v = this.filters.getRawValue();
        return { from: isoDate(v.from), to: isoDate(v.to), groupBy: v.groupBy, creditorId: v.creditor[0] ?? null };
      }),
    ),
    { initialValue: { from: this.range.from, to: this.range.to, groupBy: 'NONE', creditorId: null } as RecoveryFilterValue, equal: sameValue },
  );
  protected readonly problem = computed(() => recoveryRangeProblem(this.value().from, this.value().to));

  protected readonly report = rxResource({
    params: (): ExpectedRecoveryFilter | undefined => {
      const v = this.value();
      return this.problem() ? undefined : { from: v.from!, to: v.to!, groupBy: v.groupBy, creditorId: v.creditorId, portfolioId: null };
    },
    stream: ({ params }) => this.api.expectedRecovery(params),
  });
  protected readonly data = computed<ExpectedRecoveryReport | null>(() => (this.report.hasValue() ? (this.report.value() ?? null) : null));
  protected readonly errorText = computed(() => {
    const e = toApiError(this.report.error());
    return (e.code && SCORING_ERROR_LABELS[e.code]) || userMessage(e);
  });
  protected readonly totals = computed(() => recoveryTotals(this.data()?.lines ?? []));
  protected readonly grouped = computed(() => (this.data()?.groupBy ?? 'NONE') !== 'NONE');

  private readonly keys = (group: RecoveryGroupBy) => () =>
    this.data()?.groupBy === group ? this.data()!.lines.map((l) => l.groupKey).filter((k) => k !== '-' && k !== 'ALL') : [];
  private readonly creditorNames: NameMap = nameMap(inject(CreditorNames), this.keys('CREDITOR'));
  private readonly portfolioNames: NameMap = nameMap(inject(PortfolioNames), this.keys('PORTFOLIO'));
  private readonly userNames: NameMap = nameMap(inject(UserNames), this.keys('ASSIGNEE'), { batch: USER_NAMES_BATCH });

  protected readonly rows = computed(() => recoveryRows(this.data()?.lines ?? [], (k) => this.groupName(k)));
  protected readonly ratioDetail = computed(() => {
    const t = this.totals();
    return `${formatAmount(t.realizedAmount)} de ${formatAmount(t.expectedAmount)} esperados`;
  });
  protected readonly chartSummary = computed(() => {
    const lines = this.data()?.lines ?? [];
    const best = lines.filter((l) => dec(l.ratio) !== null).sort((a, b) => dec(b.ratio)! - dec(a.ratio)!)[0];
    return best
      ? `Valor esperado pelo score e valor recuperado por grupo. Maior razão: ${this.groupName(best.groupKey)} (${ratioText(best.ratio)}).`
      : 'Valor esperado pelo score e valor recuperado por grupo.';
  });

  protected groupName(key: string): string {
    switch (this.data()?.groupBy) {
      case 'CREDITOR':
        return this.creditorNames.name(key);
      case 'PORTFOLIO':
        return this.portfolioNames.name(key);
      case 'ASSIGNEE':
        return key === '-' ? 'Sem responsável' : this.userNames.name(key);
      default:
        return 'Total do período';
    }
  }

  protected decimalCount(value: number | string | null): string {
    const n = dec(value);
    return n === null ? '—' : new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 1 }).format(n);
  }

  protected readonly percent = percent;
}
