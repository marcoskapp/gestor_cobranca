import { NgTemplateOutlet } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NgbDropdownModule } from '@ng-bootstrap/ng-bootstrap';
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
import { nameMap } from '../../../shared/data/name-map';
import { valueOr } from '../../../shared/data/resource-value';
import { AnalyticsFilters } from '../../analytics/components/analytics-filters';
import { DrillLink } from '../../analytics/components/drill-link';
import { AnalyticsFilter } from '../../analytics/data/analytics.models';
import { DrillTarget, drillAccess, ledgerTarget } from '../../analytics/data/drill-down';
import { zonedNow } from '../../collection/data/collection.rules';
import { CreditorNames, PortfolioNames } from '../../organization/data/organization.lookups';
import { WaterfallChart } from '../components/waterfall-chart';
import { MarginApi } from '../data/costing.api';
import { CATEGORY_LABELS, CATEGORY_SHORT_LABELS, COST_SETTINGS_ROUTE } from '../data/costing.labels';
import { COST_CATEGORIES, CostCategory, CreditorMargin, MarginFilter, MarginLine, PortfolioMargin } from '../data/costing.models';
import { marginUrlState } from '../data/margin-url-state';
import {
  MarginSort,
  MarginSortKey,
  WaterfallStep,
  asAnalyticsFilter,
  countText,
  fromAnalyticsFilter,
  hoursText,
  isNegative,
  marginShort,
  marginText,
  monthLabel,
  num,
  rateText,
  refreshPeriod,
  sortMarginRows,
  warningViews,
  waterfallSteps,
} from '../data/margin.rules';

/** Coluna ordenável da tabela por credor (rótulo, chave e indicador do "i"). */
interface SortColumn {
  key: MarginSortKey;
  label: string;
  info: string;
}

/** Indicadores das categorias de custo no catálogo (um "i" por coluna). */
const CATEGORY_INFO: Readonly<Record<CostCategory, string>> = {
  HUMAN_DIRECT: 'analytics.margin.cost-human-direct',
  AI: 'analytics.margin.cost-ai',
  TELEPHONY: 'analytics.margin.cost-telephony',
  MESSAGING: 'analytics.margin.cost-messaging',
  VARIABLE_COMMISSION: 'analytics.margin.cost-commission',
  LEGAL: 'analytics.margin.cost-legal',
  HUMAN_IDLE: 'analytics.margin.cost-idle',
  OVERHEAD: 'analytics.margin.cost-overhead',
};

/**
 * Painel "Margem por carteira" (margem-por-carteira.md §6.1, fase 1): quanto cada credor e carteira rende para a
 * assessoria depois do custo operacional estimado — pessoas, IA, telefonia, mensagens, comissão variável, jurídico,
 * ociosidade e custos fixos rateados. Filtros e ordenação na URL; período padrão = último mês fechado. Nenhum valor por
 * pessoa: o servidor só devolve agregados por credor, carteira e mês.
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
    NgbDropdownModule,
    NgTemplateOutlet,
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
  protected readonly data = rxResource({ params: () => this.state.filter(), stream: ({ params }) => this.api.margin(params) });
  protected readonly report = valueOr(this.data, undefined);

  protected readonly categories = COST_CATEGORIES;
  protected readonly categoryLabels = CATEGORY_LABELS;
  protected readonly categoryShort = CATEGORY_SHORT_LABELS;
  protected readonly categoryInfo = CATEGORY_INFO;
  protected readonly settingsRoute = COST_SETTINGS_ROUTE;
  protected readonly settingsPermissions = [Permission.COST_MANAGE, Permission.COST_RATE_MANAGE];
  protected readonly money = formatAmount;
  protected readonly rate = rateText;
  protected readonly marginText = marginText;
  protected readonly marginShort = marginShort;
  protected readonly negative = isNegative;
  protected readonly count = countText;
  protected readonly hours = hoursText;
  protected readonly monthLabel = monthLabel;

  protected readonly canExport = computed(() => this.auth.hasPermission(Permission.REPORT_EXPORT));
  /** Quem não vê todos os credores recebe ociosidade e fixos só dos próprios credores, sem total da assessoria (§7). */
  protected readonly partialScope = computed(() => this.auth.user()?.allCreditors === false);
  /** Reapurar: o servidor exige `COST_MANAGE` ou `COST_RATE_MANAGE` e todos os credores. */
  protected readonly canRefresh = computed(
    () => !this.partialScope() && (this.auth.hasPermission(Permission.COST_MANAGE) || this.auth.hasPermission(Permission.COST_RATE_MANAGE)),
  );
  protected readonly exporting = signal(false);
  protected readonly refreshing = signal(false);
  /** Colunas por categoria de custo (tabela larga) só quando pedidas. */
  protected readonly showCategories = signal(false);
  protected readonly expanded = signal<ReadonlySet<string>>(new Set());

  protected readonly warnings = computed(() => warningViews(this.report()?.warnings));
  protected readonly empty = computed(() => {
    const r = this.report();
    return !!r && r.creditors.length === 0 && num(r.totals.revenue) === 0 && num(r.totals.totalCost) === 0;
  });

  // ------------------------------------------------------------ nomes (só ids na resposta)
  private readonly canCreditors = computed(() => this.auth.hasPermission(Permission.CREDITOR_VIEW));
  private readonly canPortfolios = computed(() => this.auth.hasPermission(Permission.PORTFOLIO_VIEW));
  private readonly creditorNames = nameMap(inject(CreditorNames), () => (this.canCreditors() ? (this.report()?.creditors ?? []).map((c) => c.creditorId) : []));
  private readonly portfolioNames = nameMap(inject(PortfolioNames), () =>
    this.canPortfolios() ? (this.report()?.creditors ?? []).flatMap((c) => c.portfolios.map((p) => p.portfolioId)) : [],
  );

  protected creditorName(row: CreditorMargin): string {
    return row.name || (this.canCreditors() ? this.creditorNames.name(row.creditorId) : 'Credor');
  }

  protected portfolioName(row: PortfolioMargin): string {
    if (!row.portfolioId) {
      return 'Sem carteira (custos do credor)';
    }
    return this.canPortfolios() ? this.portfolioNames.name(row.portfolioId) : 'Carteira';
  }

  // ------------------------------------------------------------ tabela
  protected readonly sortColumns: readonly SortColumn[] = [
    { key: 'recovered', label: 'Recuperado', info: 'analytics.margin.recovered' },
    { key: 'revenue', label: 'Receita', info: 'analytics.margin.revenue' },
  ];
  protected readonly resultColumns: readonly SortColumn[] = [
    { key: 'directCost', label: 'Custo direto', info: 'analytics.margin.direct-cost' },
    { key: 'totalCost', label: 'Custo total', info: 'analytics.margin.total-cost' },
    { key: 'contributionMargin', label: 'Margem de contribuição', info: 'analytics.margin.contribution' },
    { key: 'netMargin', label: 'Margem líquida', info: 'analytics.margin.net' },
    { key: 'roi', label: 'ROI', info: 'analytics.margin.roi' },
  ];
  protected readonly columnCount = computed(() => 1 + this.sortColumns.length + (this.showCategories() ? this.categories.length : 0) + this.resultColumns.length);

  protected readonly creditorRows = computed(() => {
    const sort = this.state.sort();
    return sortMarginRows(this.report()?.creditors ?? [], sort, (r) => this.creditorName(r)).map((row) => ({
      row,
      portfolios: sortMarginRows(row.portfolios ?? [], sort, (p) => this.portfolioName(p)),
    }));
  });

  protected sortBy(key: MarginSortKey): void {
    const current = this.state.sort();
    // primeira escolha de uma coluna de valor: maior primeiro; nome: A→Z
    const next: MarginSort = current.key === key ? { key, desc: !current.desc } : { key, desc: key !== 'name' };
    this.state.setSort(next);
  }

  protected ariaSort(key: MarginSortKey): 'ascending' | 'descending' | null {
    const s = this.state.sort();
    return s.key === key ? (s.desc ? 'descending' : 'ascending') : null;
  }

  protected toggle(creditorId: string): void {
    this.expanded.update((set) => {
      const next = new Set(set);
      if (next.has(creditorId)) {
        next.delete(creditorId);
      } else {
        next.add(creditorId);
      }
      return next;
    });
  }

  protected cost(line: MarginLine, category: CostCategory): string {
    return formatAmount(line.costs?.[category] ?? 0);
  }

  // ------------------------------------------------------------ drill-down: recuperado e receita → razão de comissão
  private readonly access = drillAccess();

  private ledger(filter: MarginFilter, label: string, period?: { from: string; to: string }): DrillTarget | null {
    return this.access(ledgerTarget(asAnalyticsFilter(filter), {}, label, period));
  }

  protected readonly totalLink = computed(() => this.ledger(this.state.filter(), 'ver os recebimentos e a comissão no razão'));

  protected creditorLink(row: CreditorMargin): DrillTarget | null {
    return this.ledger({ ...this.state.filter(), creditorIds: [row.creditorId] }, 'ver os recebimentos e a comissão do credor no razão');
  }

  protected portfolioLink(creditor: CreditorMargin, row: PortfolioMargin): DrillTarget | null {
    return row.portfolioId
      ? this.ledger({ ...this.state.filter(), creditorIds: [creditor.creditorId], portfolioIds: [row.portfolioId] }, 'ver os recebimentos e a comissão da carteira no razão')
      : null;
  }

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
    return t ? `${formatAmount(t.directCost)} direto + ${formatAmount(num(t.totalCost) - num(t.directCost))} de ociosidade e fixos` : '';
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

  protected export(format: 'xlsx' | 'csv'): void {
    this.exporting.set(true);
    this.api
      .export(this.state.filter(), format)
      .pipe(finalize(() => this.exporting.set(false)))
      .subscribe((name) => this.notifications.success(`Relatório exportado: ${name}. A exportação fica registrada no log de acesso.`));
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
      });
  }
}
