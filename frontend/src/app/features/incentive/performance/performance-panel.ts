import { Component, computed, inject } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule } from '@angular/forms';
import { debounceTime, map, of, startWith } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { EntityPicker } from '../../../shared/components/entity-picker/entity-picker';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { sameValue } from '../../../shared/data/same-value';
import { FORM_FIELD } from '../../../shared/forms/field';
import { UserLookup } from '../../admin/data/user.lookup';
import { USER_NAMES_BATCH, UserNames } from '../../admin/data/user-names';
import { BarChart } from '../../analytics/components/bar-chart';
import { ChartRow } from '../../analytics/components/chart.models';
import { KpiCard } from '../../analytics/components/kpi-card';
import { count, fixed2, seconds } from '../../analytics/data/analytics.format';
import { TeamLookup } from '../../collection/data/collection.lookups';
import { zonedNow } from '../../collection/data/collection.rules';
import { CreditorLookup, CreditorNames, PortfolioNames } from '../../organization/data/organization.lookups';
import { PerformanceApi } from '../data/incentive.api';
import { CHANNEL_LABELS, DELAY_BUCKET_LABELS, GROUP_BY_LABELS, PLAN_DELAY_BUCKETS, UNAVAILABLE_METRIC_LABELS } from '../data/incentive.labels';
import { IndicatorRow, PerformanceGroupBy, PerformanceQuery } from '../data/incentive.models';
import { fraction, monthEnd, monthOf, monthOptions, monthStart, score } from '../data/incentive.rules';

export type PerformanceScope = 'OWN' | 'TEAM' | 'ALL';

/** Escopo do painel pela maior permissão de desempenho (o servidor aplica o mesmo escopo). */
export function performanceScope(has: (p: string) => boolean): PerformanceScope {
  return has(Permission.PERFORMANCE_VIEW_ALL) ? 'ALL' : has(Permission.PERFORMANCE_VIEW_TEAM) ? 'TEAM' : 'OWN';
}

/** Agrupamentos oferecidos por escopo: o negociador vê só o total e os recortes da própria carteira. */
export function groupOptions(scope: PerformanceScope): PerformanceGroupBy[] {
  const common: PerformanceGroupBy[] = ['CREDITOR', 'PORTFOLIO', 'DELAY_BUCKET', 'CHANNEL'];
  return scope === 'OWN' ? ['NONE', ...common] : ['USER', 'TEAM', 'NONE', ...common];
}

/** Período do painel: do dia 1º ao fim do mês (o mês corrente vai até hoje). */
export function monthRange(month: string, today: string): { from: string; to: string } {
  const end = monthEnd(month);
  return { from: monthStart(month), to: end > today ? today : end };
}

/**
 * Painel de desempenho dos negociadores (§2 do desenho): esforço, conversão, resultado e qualidade,
 * com visão própria, da equipe ou de todos conforme a permissão. Reaproveita os gráficos do
 * `analytics` (cartões de indicador e barras horizontais acessíveis).
 */
@Component({
  selector: 'app-incentive-performance-panel',
  imports: [ReactiveFormsModule, ...FORM_FIELD, Amount, BarChart, EmptyState, EntityPicker, KpiCard, PageHeader, Skeleton],
  template: `
    <app-page-header [title]="title()" subtitle="Indicadores dos negociadores humanos a partir dos fatos de cobrança, negociação e pagamento, com as bases sempre visíveis." />
    <form class="app-filters" data-tour="filters" [formGroup]="form" role="search" aria-label="Filtrar indicadores">
      <app-field label="Mês">
        <select appInput formControlName="month">
          @for (m of months; track m.value) {
            <option [ngValue]="m.value">{{ m.label }}</option>
          }
        </select>
      </app-field>
      <app-field label="Agrupar">
        <select appInput formControlName="groupBy">
          @for (g of groups(); track g) {
            <option [ngValue]="g">{{ groupLabels[g] }}</option>
          }
        </select>
      </app-field>
      <app-field label="Faixa de atraso">
        <select appInput formControlName="delayBucket">
          <option value="">Todas</option>
          @for (b of buckets; track b) {
            <option [ngValue]="b">{{ bucketLabels[b] }}</option>
          }
        </select>
      </app-field>
      <div class="app-filters__wide">
        <app-entity-picker [control]="form.controls.creditor" [source]="creditorSource" label="Credor" [single]="true" />
      </div>
      @if (scope() !== 'OWN' && canUsers()) {
        <div class="app-filters__wide">
          <app-entity-picker [control]="form.controls.user" [source]="userSource" label="Negociador" [single]="true" />
        </div>
      }
      @if (scope() !== 'OWN') {
        <div class="app-filters__wide">
          <app-entity-picker [control]="form.controls.team" [source]="teamSource" label="Equipe" [single]="true" />
        </div>
      }
    </form>

    @if (totalData.status() === 'error') {
      <app-empty-state tone="error" icon="exclamation-circle" title="Não foi possível carregar os indicadores." message="Verifique os filtros e tente novamente.">
        <button class="btn btn-primary" type="button" (click)="reload()">Tentar novamente</button>
      </app-empty-state>
    } @else if (!total() && totalData.isLoading()) {
      <div aria-busy="true" aria-label="Carregando indicadores"><app-skeleton variant="card" [rows]="4" /></div>
    } @else if (!total()) {
      <app-empty-state icon="speedometer2" title="Sem atividade no período." message="Nenhum fato de desempenho para os filtros escolhidos." />
    } @else {
      @let t = total()!;
      <section class="in-kpis" aria-label="Indicadores do período" data-tour="performance-kpis">
        <app-kpi-card label="Casos trabalhados" [detail]="count(t.effort.attempts) + ' tentativas'">{{ count(t.effort.workedCases) }}</app-kpi-card>
        <app-kpi-card label="Contatos efetivos" [detail]="pct(t.effort.effectiveContactRate) + ' das tentativas'">{{ count(t.effort.effectiveContacts) }}</app-kpi-card>
        <app-kpi-card label="Acordos" [detail]="rate(t.conversion.agreementsPerLoggedHour) + ' por hora logada'">{{ count(t.conversion.agreements) }}</app-kpi-card>
        <app-kpi-card label="Recuperado" [detail]="'Taxa de recuperação ' + pct(t.financial.recoveryRate)"><app-amount [value]="t.financial.recoveredAmount ?? 0" [compact]="true" /></app-kpi-card>
        <app-kpi-card label="Recuperado × esperado" detail="Pelo score da carteira atribuída">{{ pct(t.financial.expectedRecoveryRatio) }}</app-kpi-card>
        <app-kpi-card label="Retornos cumpridos" [detail]="count(t.effort.callbacksKept) + ' de ' + count(t.effort.callbacksScheduled)">{{ pct(t.effort.callbacksKeptRate) }}</app-kpi-card>
        <app-kpi-card label="Promessas cumpridas" [detail]="count(t.conversion.promisesKept) + ' de ' + count(t.conversion.promisesDue)">{{ pct(t.conversion.promisesKeptRate) }}</app-kpi-card>
        <app-kpi-card label="Quebra precoce (60 dias)" [detail]="count(t.quality.safraAgreements) + ' acordos na safra'">{{ pct(t.quality.earlyBreak60) }}</app-kpi-card>
        <app-kpi-card label="Nota de monitoria" detail="Média das avaliações (0 a 10)">{{ scoreText(t.quality.qaScoreAverage) }}</app-kpi-card>
        <app-kpi-card label="Tempo médio de atendimento" [detail]="count(t.effort.answeredCalls) + ' chamadas atendidas'">{{ seconds(t.effort.averageHandlingSeconds) }}</app-kpi-card>
      </section>

      @if (grouped(); as rows) {
        @if (rows.length) {
          <section class="app-card" data-tour="performance-chart">
            <app-bar-chart [title]="'Recuperado ' + groupLabels[groupBy()].toLowerCase()" [categoryHeader]="groupHeader()" [summary]="chartSummary()"
                           [series]="[{ name: 'Recuperado', color: '--app-chart-1' }]" [rows]="chartRows()" noteHeader="Acordos" />
          </section>
        }
        <section class="app-card" aria-labelledby="pf-table">
          <h2 class="app-card__title" id="pf-table">{{ groupLabels[groupBy()] }}</h2>
          <div class="in-scroll">
            <table class="in-table">
              <caption class="visually-hidden">Indicadores {{ groupLabels[groupBy()].toLowerCase() }}</caption>
              <thead>
                <tr>
                  <th scope="col">{{ groupHeader() }}</th>
                  <th scope="col" class="in-end">Casos</th>
                  <th scope="col" class="in-end">Contato efetivo</th>
                  <th scope="col" class="in-end">Acordos</th>
                  <th scope="col" class="in-end">Recuperado</th>
                  <th scope="col" class="in-end">Taxa de recuperação</th>
                  <th scope="col" class="in-end">Promessas cumpridas</th>
                  <th scope="col" class="in-end">Quebra 60d</th>
                  <th scope="col" class="in-end">Monitoria</th>
                </tr>
              </thead>
              <tbody>
                @for (r of rows; track r.groupKey) {
                  <tr>
                    <th scope="row">{{ label(r.groupKey) }}</th>
                    <td class="in-end">{{ count(r.effort.workedCases) }}</td>
                    <td class="in-end">{{ pct(r.effort.effectiveContactRate) }}</td>
                    <td class="in-end">{{ count(r.conversion.agreements) }}</td>
                    <td class="in-end"><app-amount [value]="r.financial.recoveredAmount ?? 0" /></td>
                    <td class="in-end">{{ pct(r.financial.recoveryRate) }}</td>
                    <td class="in-end">{{ pct(r.conversion.promisesKeptRate) }}</td>
                    <td class="in-end">{{ pct(r.quality.earlyBreak60) }}</td>
                    <td class="in-end">{{ scoreText(r.quality.qaScoreAverage) }}</td>
                  </tr>
                } @empty {
                  <tr><td colspan="9">Nenhuma atividade registrada no período.</td></tr>
                }
              </tbody>
            </table>
          </div>
        </section>
      }
      @if (unavailable().length) {
        <p class="app-muted in-small" role="note">Ainda sem fonte de dados (aparecem como "—"): {{ unavailable().join(', ') }}.</p>
      }
    }
  `,
  styleUrl: '../incentive.scss',
})
export class PerformancePanel {
  private readonly api = inject(PerformanceApi);
  private readonly auth = inject(AuthService);
  private readonly today = zonedNow().date;
  protected readonly creditorSource = inject(CreditorLookup);
  protected readonly userSource = inject(UserLookup);
  protected readonly teamSource = inject(TeamLookup);
  protected readonly months = monthOptions(this.today, 12);
  protected readonly groupLabels = GROUP_BY_LABELS;
  protected readonly buckets = PLAN_DELAY_BUCKETS;
  protected readonly bucketLabels = DELAY_BUCKET_LABELS;
  protected readonly count = count;
  protected readonly seconds = seconds;
  protected readonly pct = fraction;
  protected readonly scoreText = score;
  protected readonly scope = computed(() => performanceScope((p) => this.auth.hasPermission(p)));
  protected readonly canUsers = computed(() => this.auth.hasPermission(Permission.USER_VIEW));
  protected readonly groups = computed(() => groupOptions(this.scope()));
  protected readonly title = computed(() => ({ OWN: 'Meu desempenho', TEAM: 'Desempenho da equipe', ALL: 'Desempenho dos negociadores' })[this.scope()]);

  private readonly fb = inject(FormBuilder).nonNullable;
  protected readonly form = this.fb.group({
    month: [monthOf(this.today)],
    groupBy: this.fb.control<PerformanceGroupBy>(groupOptions(performanceScope((p) => this.auth.hasPermission(p)))[0]),
    delayBucket: [''],
    creditor: this.fb.control<string[]>([]),
    user: this.fb.control<string[]>([]),
    team: this.fb.control<string[]>([]),
  });
  private readonly query = toSignal(this.form.valueChanges.pipe(startWith(null), debounceTime(250), map(() => this.current())), {
    initialValue: this.current(),
    equal: sameValue,
  });
  protected readonly groupBy = computed(() => this.query().groupBy);
  protected readonly totalData = rxResource({ params: () => ({ ...this.query(), groupBy: 'NONE' as const }), stream: ({ params }) => this.api.indicators(params) });
  private readonly groupData = rxResource({
    params: () => (this.query().groupBy === 'NONE' ? undefined : this.query()),
    stream: ({ params }) => (params ? this.api.indicators(params) : of(undefined)),
  });
  private readonly totalReport = valueOr(this.totalData, undefined);
  protected readonly total = computed(() => this.totalReport()?.rows[0] ?? null);
  private readonly groupReport = valueOr(this.groupData, undefined);
  protected readonly grouped = computed<IndicatorRow[] | null>(() => (this.query().groupBy === 'NONE' ? null : (this.groupReport()?.rows ?? null)));
  protected readonly unavailable = computed(() => (this.totalReport()?.unavailableMetrics ?? []).map((m) => UNAVAILABLE_METRIC_LABELS[m] ?? m));

  private readonly keys = computed(() => (this.grouped() ?? []).map((r) => r.groupKey));
  private readonly userNames = nameMap(inject(UserNames), () => (this.groupBy() === 'USER' ? this.keys() : []), { batch: USER_NAMES_BATCH });
  private readonly teamNames = nameMap(inject(TeamLookup), () => (this.groupBy() === 'TEAM' ? this.keys() : []));
  private readonly creditorNames = nameMap(inject(CreditorNames), () => (this.groupBy() === 'CREDITOR' ? this.keys().filter((k) => k !== 'UNKNOWN') : []));
  private readonly portfolioNames = nameMap(inject(PortfolioNames), () => (this.groupBy() === 'PORTFOLIO' ? this.keys().filter((k) => k !== 'UNKNOWN') : []));

  protected readonly groupHeader = computed(
    () => ({ NONE: 'Total', USER: 'Negociador', TEAM: 'Equipe', CREDITOR: 'Credor', PORTFOLIO: 'Carteira', DELAY_BUCKET: 'Faixa de atraso', CHANNEL: 'Canal' })[this.groupBy()],
  );
  protected readonly chartRows = computed<ChartRow[]>(() =>
    [...(this.grouped() ?? [])]
      .sort((a, b) => (b.financial.recoveredAmount ?? 0) - (a.financial.recoveredAmount ?? 0))
      .slice(0, 15)
      .map((r) => ({
        key: r.groupKey,
        label: this.label(r.groupKey),
        values: [Math.max(0, r.financial.recoveredAmount ?? 0)],
        display: [formatAmount(r.financial.recoveredAmount ?? 0)],
        note: `${count(r.conversion.agreements)} acordos`,
      })),
  );
  protected readonly chartSummary = computed(() => {
    const top = this.chartRows()[0];
    return top ? `Valor recuperado no período, ${GROUP_BY_LABELS[this.groupBy()].toLowerCase()}. Maior: ${top.label}, ${top.display[0]}.` : 'Valor recuperado no período.';
  });

  protected label(key: string): string {
    switch (this.groupBy()) {
      case 'USER':
        return this.userNames.name(key);
      case 'TEAM':
        return this.teamNames.name(key);
      case 'CREDITOR':
        return key === 'UNKNOWN' ? 'Não informado' : this.creditorNames.name(key);
      case 'PORTFOLIO':
        return key === 'UNKNOWN' ? 'Sem carteira' : this.portfolioNames.name(key);
      case 'DELAY_BUCKET':
        return DELAY_BUCKET_LABELS[key as keyof typeof DELAY_BUCKET_LABELS] ?? key;
      case 'CHANNEL':
        return CHANNEL_LABELS[key] ?? key;
      default:
        return 'Total';
    }
  }

  protected rate(value: number | null): string {
    return fixed2(value);
  }

  protected reload(): void {
    this.totalData.reload();
    this.groupData.reload();
  }

  private current(): PerformanceQuery {
    const v = this.form.getRawValue();
    const { from, to } = monthRange(v.month, this.today);
    return {
      from,
      to,
      groupBy: v.groupBy,
      delayBucket: v.delayBucket || null,
      creditorId: v.creditor[0] ?? null,
      userId: v.user[0] ?? null,
      teamId: v.team[0] ?? null,
      portfolioId: null,
      channel: null,
    };
  }
}
