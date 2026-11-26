import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { finalize } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { Permission } from '../../../core/auth/permission';
import { NotificationService } from '../../../core/notification/notification.service';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { ConfigLink } from '../../../shared/components/config-link/config-link';
import { EmptyState } from '../../../shared/components/empty-state/empty-state';
import { Icon } from '../../../shared/components/icon/icon';
import { InfoHint } from '../../../shared/components/info-hint/info-hint';
import { KpiCard } from '../../../shared/components/kpi-card/kpi-card';
import { PageHeader } from '../../../shared/components/page-header/page-header';
import { Skeleton } from '../../../shared/components/skeleton/skeleton';
import { valueOr } from '../../../shared/data/resource-value';
import { AnalyticsFilters } from '../../analytics/components/analytics-filters';
import { DrillLink } from '../../../shared/components/charts/drill-link';
import { AnalyticsFilter } from '../../analytics/data/analytics.models';
import { drillAccess, ledgerTarget } from '../../analytics/data/drill-down';
import { zonedNow } from '../../collection/data/collection.rules';
import { MarginBasisBar } from '../components/margin-basis-bar';
import { MarginExport } from '../components/margin-export';
import { MarginScatter } from '../components/margin-scatter';
import { MarginTable } from '../components/margin-table';
import { MarginWarnings } from '../components/margin-warnings';
import { WaterfallChart } from '../components/waterfall-chart';
import { MarginApi } from '../data/costing.api';
import { CHANNEL_ITEM_LABELS, COST_SETTINGS_ROUTE } from '../data/costing.labels';
import { MarginFilter, MarginLine } from '../data/costing.models';
import { marginNames } from '../data/margin-names';
import { ScatterPoint, scatterModel, scatterSummary } from '../data/margin-scatter.rules';
import { marginUrlState } from '../data/margin-url-state';
import {
  WaterfallStep,
  adjustmentViews,
  asAnalyticsFilter,
  countText,
  fromAnalyticsFilter,
  hoursText,
  isNegative,
  marginFilterParams,
  marginShort,
  marginText,
  monthLabel,
  num,
  pendingInvoices,
  rateText,
  refreshPeriod,
  signedAmount,
  waterfallSteps,
} from '../data/margin.rules';

/**
 * Painel "Margem por carteira" (margem-por-carteira.md §6.1, fase 1): quanto cada credor e carteira rende para a
 * assessoria depois do custo operacional estimado — pessoas, IA, telefonia, mensagens, comissão variável, jurídico,
 * ociosidade e custos fixos rateados. Filtros e ordenação na URL; período padrão = último mês fechado. Nenhum valor por
 * pessoa: o servidor só devolve agregados por credor, carteira e mês. A tabela por credor é a compacta (colunas
 * essenciais, 10 credores); a completa fica no relatório (`/paineis/margem/relatorio`, §15), aberto em nova aba.
 */
@Component({
  selector: 'app-margin-panel',
  imports: [
    Amount,
    AnalyticsFilters,
    ConfigLink,
    DrillLink,
    EmptyState,
    Icon,
    InfoHint,
    KpiCard,
    MarginBasisBar,
    MarginExport,
    MarginScatter,
    MarginTable,
    MarginWarnings,
    PageHeader,
    Skeleton,
    WaterfallChart,
  ],
  templateUrl: './margin-panel.html',
  styleUrl: './margin-panel.scss',
})
export class MarginPanel {
  private readonly api = inject(MarginApi);
  private readonly auth = inject(AuthService);
  private readonly notifications = inject(NotificationService);

  protected readonly state = marginUrlState();
  protected readonly analyticsFilter = computed(() => asAnalyticsFilter(this.state.filter()));
  protected readonly data = rxResource({
    params: () => ({ filter: this.state.filter(), basis: this.state.basis() }),
    stream: ({ params }) => this.api.margin(params.filter, params.basis),
  });
  protected readonly report = valueOr(this.data, undefined);
  /**
   * Tendência de 6 meses por credor e carteira (§14.1): pedido à parte, com os mesmos filtros, para não pesar a
   * resposta principal; falha ou demora só afetam a coluna "Tendência".
   */
  protected readonly trendData = rxResource({
    params: () => ({ filter: this.state.filter(), basis: this.state.basis() }),
    stream: ({ params }) => this.api.trend(params.filter, params.basis),
  });
  protected readonly trend = valueOr(this.trendData, undefined);
  protected readonly names = marginNames(this.report);

  protected readonly settingsRoute = COST_SETTINGS_ROUTE;
  protected readonly money = formatAmount;
  protected readonly marginShort = marginShort;
  protected readonly negative = isNegative;
  protected readonly hours = hoursText;
  protected readonly monthLabel = monthLabel;

  /** Quem não vê todos os credores recebe ociosidade e fixos só dos próprios credores, sem total da assessoria (§7). */
  protected readonly partialScope = computed(() => this.auth.user()?.allCreditors === false);
  /** Reapurar: o servidor exige `COST_MANAGE` ou `COST_RATE_MANAGE` e todos os credores. */
  protected readonly canRefresh = computed(
    () => !this.partialScope() && (this.auth.hasPermission(Permission.COST_MANAGE) || this.auth.hasPermission(Permission.COST_RATE_MANAGE)),
  );
  // ------------------------------------------------------------ base dos custos: estimada ou conciliada com a fatura (§13)
  protected readonly reconciled = computed(() => this.state.basis() === 'RECONCILED');
  protected readonly adjustments = computed(() => adjustmentViews(this.report()?.totals, this.state.basis(), this.report()?.reconciliation));
  protected readonly adjustmentTotal = computed(() => this.adjustments().reduce((sum, a) => sum + a.adjustment, 0));
  protected readonly pending = computed(() => pendingInvoices(this.report()?.reconciliation));
  /** Faturas ao lado da estimativa: só na visão da assessoria inteira (o servidor manda nulo nos demais casos). */
  protected readonly invoiceLines = computed(() => this.report()?.reconciliation?.invoices ?? []);
  protected readonly itemLabels = CHANNEL_ITEM_LABELS;
  protected readonly signed = signedAmount;
  /** Atalho para a aba Faturas: quem pode informar (a aba exige todos os credores). */
  protected readonly invoicePermissions = [Permission.COST_MANAGE];
  protected readonly invoiceTab = { aba: 'faturas' };
  protected readonly invoiceLink = computed(() => !this.partialScope());

  protected readonly refreshing = signal(false);

  protected readonly empty = computed(() => {
    const r = this.report();
    return !!r && r.creditors.length === 0 && num(r.totals.revenue) === 0 && num(r.totals.totalCost) === 0;
  });

  // ------------------------------------------------------------ mapa de dispersão (carteiras do período)
  protected readonly scatter = computed(() =>
    scatterModel(
      this.report()?.creditors ?? [],
      (c) => this.names.creditorName(c),
      (_, portfolioId) => this.names.portfolioNameById(portfolioId),
    ),
  );
  protected readonly scatterSummary = computed(() => scatterSummary(this.scatter()));
  /** Filtro da carteira: mesmo período, o credor e a carteira do ponto. */
  private scatterFilter(p: ScatterPoint): MarginFilter {
    return { ...this.state.filter(), creditorIds: [p.creditorId], portfolioIds: [p.portfolioId] };
  }
  protected readonly scatterParams = (p: ScatterPoint) => marginFilterParams(this.scatterFilter(p));

  /** Clique ou Enter num ponto: o painel abre filtrado pela carteira, como um passo novo do histórico ("voltar"). */
  protected openPortfolio(p: ScatterPoint): void {
    this.state.open(this.scatterFilter(p));
  }

  // ------------------------------------------------------------ drill-down: receita total → razão de comissão
  private readonly access = drillAccess();
  protected readonly totalLink = computed(() =>
    this.access(ledgerTarget(asAnalyticsFilter(this.state.filter()), {}, 'ver os recebimentos e a comissão no razão')),
  );

  // ------------------------------------------------------------ cascata e evolução
  protected readonly steps = computed<WaterfallStep[]>(() => {
    const t = this.report()?.totals;
    return t ? waterfallSteps(t) : [];
  });
  protected readonly waterfallSummary = computed(() => {
    const t = this.report()?.totals;
    if (!t) {
      return '';
    }
    const biggest = [...this.steps()].filter((s) => s.kind === 'cost').sort((a, b) => b.start - b.end - (a.start - a.end))[0];
    const base = `Receita de ${formatAmount(t.revenue)}, custo total de ${formatAmount(t.totalCost)} e margem líquida de ${marginText(t.netMargin)}.`;
    return biggest && biggest.start !== biggest.end ? `${base} Maior custo: ${biggest.label.toLowerCase()}, ${formatAmount(biggest.start - biggest.end)}.` : base;
  });

  protected readonly monthSteps = computed<WaterfallStep[]>(() =>
    (this.report()?.months ?? []).map((m) => ({
      key: m.month,
      label: monthLabel(m.month),
      kind: 'subtotal' as const,
      start: 0,
      end: num(m.netMargin),
      display: marginText(m.netMargin),
    })),
  );
  protected readonly monthNotes = computed(() =>
    Object.fromEntries(
      (this.report()?.months ?? []).map((m) => [
        m.month,
        `receita ${formatAmount(m.revenue)} · custo ${formatAmount(m.totalCost)} · margem ${rateText(m.netMarginRate)} da receita`,
      ]),
    ),
  );
  protected readonly monthSummary = computed(() => {
    const months = this.report()?.months ?? [];
    if (!months.length) {
      return '';
    }
    const negatives = months.filter((m) => isNegative(m.netMargin)).length;
    const last = months[months.length - 1];
    return `Margem líquida de ${months.length} ${months.length === 1 ? 'mês' : 'meses'}; último: ${monthLabel(last.month)}, ${marginText(last.netMargin)}.${negatives ? ` ${negatives} com margem negativa.` : ''}`;
  });

  // ------------------------------------------------------------ cartões
  protected readonly costDetail = computed(() => {
    const t = this.report()?.totals;
    if (!t) {
      return '';
    }
    const base = `${formatAmount(t.directCost)} direto + ${formatAmount(num(t.totalCost) - num(t.directCost))} de ociosidade e fixos`;
    return this.reconciled() && this.adjustmentTotal() !== 0 ? `${base} · ajuste pela fatura ${signedAmount(this.adjustmentTotal())}` : base;
  });
  protected marginDetail(line: MarginLine, rate: MarginLine['netMarginRate'], withRoi = false): string {
    if (rate === null || rate === undefined) {
      return 'sem receita no período';
    }
    return `${rateText(rate)} da receita${withRoi ? ` · ROI ${rateText(line.roi)}` : ''}`;
  }
  protected readonly costPerRecoveredDetail = computed(() => {
    const t = this.report()?.totals;
    if (!t) {
      return '';
    }
    return t.costPerRecovered === null ? 'sem valor recuperado no período' : `${formatAmount(t.totalCost)} de custo ÷ ${formatAmount(t.recovered)} recuperado`;
  });
  protected readonly costPerSettledDetail = computed(() => {
    const t = this.report()?.totals;
    if (!t) {
      return '';
    }
    return t.settledAgreements > 0
      ? `${countText(t.settledAgreements)} ${t.settledAgreements === 1 ? 'acordo cumprido' : 'acordos cumpridos'} no período`
      : 'nenhum acordo cumprido no período';
  });

  // ------------------------------------------------------------ ações
  protected changeFilter(filter: AnalyticsFilter): void {
    this.state.update(fromAnalyticsFilter(filter));
  }

  protected refresh(): void {
    const period = refreshPeriod(this.state.filter(), zonedNow().date);
    if (!period.ok) {
      this.notifications.error(period.message);
      return;
    }
    this.refreshing.set(true);
    this.api
      .refresh(period)
      .pipe(finalize(() => this.refreshing.set(false)))
      .subscribe(() => {
        this.notifications.success('Custos do período reapurados com os cadastros atuais.');
        this.data.reload();
        this.trendData.reload();
      });
  }
}
