import { NgTemplateOutlet } from '@angular/common';
import { Component, ElementRef, computed, effect, inject, input, linkedSignal, output, signal, viewChild } from '@angular/core';
import { takeUntilDestroyed, toObservable } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { debounceTime } from 'rxjs';
import { Amount, formatAmount } from '../../../shared/components/amount/amount';
import { Icon } from '../../../shared/components/icon/icon';
import { InfoHint } from '../../../shared/components/info-hint/info-hint';
import { ShowMoreButton } from '../../../shared/components/show-more/show-more';
import { injectUrlStateWriter } from '../../../shared/data/url-state';
import { showMore } from '../../../shared/util/show-more';
import { matchesSearch, normalizeSearch } from '../../../shared/util/text-search';
import { DrillLink } from '../../../shared/components/charts/drill-link';
import { DrillTarget, drillAccess, ledgerTarget } from '../../analytics/data/drill-down';
import { CATEGORY_LABELS, CATEGORY_SHORT_LABELS, MARGIN_REPORT_ROUTE } from '../data/costing.labels';
import {
  COST_CATEGORIES,
  CostCategory,
  CreditorMargin,
  MarginBasis,
  MarginFilter,
  MarginLine,
  MarginReport,
  MarginTrend,
  PortfolioMargin,
  TrendPoint,
} from '../data/costing.models';
import { MarginNames } from '../data/margin-names';
import {
  DEFAULT_MARGIN_SORT,
  MARGIN_COMPACT_LIMIT,
  MarginSort,
  MarginSortKey,
  asAnalyticsFilter,
  compactCut,
  compactNotice,
  countText,
  isNegative,
  marginReportParams,
  marginText,
  rateText,
  sortMarginRows,
  sumMarginLines,
} from '../data/margin.rules';
import { TrendSparkline } from './trend-sparkline';

/** Coluna ordenável da tabela por credor (rótulo, chave e indicador do "i"). */
interface SortColumn {
  key: MarginSortKey;
  label: string;
  info: string;
}

/** Linha da tabela: o credor e as carteiras dele, já ordenadas. */
interface CreditorItem {
  row: CreditorMargin;
  portfolios: PortfolioMargin[];
}

/** Modo da tabela: compacto no painel (colunas essenciais, 10 credores) ou completo no relatório. */
export type MarginTableMode = 'compact' | 'full';

/** Parâmetro da URL com o termo da busca do relatório (nome de credor ou carteira: cadastro, sem dado pessoal). */
const SEARCH_PARAM = 'busca';
const CATEGORIES_PARAM = 'categorias';

/** Atraso da busca enquanto a pessoa digita. */
const SEARCH_DEBOUNCE_MS = 250;

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

const RECOVERED: SortColumn = { key: 'recovered', label: 'Recuperado', info: 'analytics.margin.recovered' };
const REVENUE: SortColumn = { key: 'revenue', label: 'Receita', info: 'analytics.margin.revenue' };
const TOTAL_COST: SortColumn = { key: 'totalCost', label: 'Custo total', info: 'analytics.margin.total-cost' };
const CONTRIBUTION: SortColumn = { key: 'contributionMargin', label: 'Margem de contribuição', info: 'analytics.margin.contribution' };
const NET_MARGIN: SortColumn = { key: 'netMargin', label: 'Margem líquida', info: 'analytics.margin.net' };
const ROI: SortColumn = { key: 'roi', label: 'ROI', info: 'analytics.margin.roi' };

/** "Ordenar por" do celular (a tabela vira blocos e o cabeçalho some); no compacto, só as colunas que ele mostra. */
const ORDER_OPTIONS: readonly { value: string; label: string; compact: boolean }[] = [
  { value: 'netMargin', label: 'Margem líquida (pior primeiro)', compact: true },
  { value: '-netMargin', label: 'Margem líquida (melhor primeiro)', compact: true },
  { value: '-revenue', label: 'Receita (maior primeiro)', compact: true },
  { value: '-recovered', label: 'Recuperado (maior primeiro)', compact: false },
  { value: '-totalCost', label: 'Custo total (maior primeiro)', compact: true },
  { value: '-roi', label: 'ROI (maior primeiro)', compact: false },
  { value: 'name', label: 'Credor (A a Z)', compact: true },
];

/**
 * Tabela "Margem por credor e carteira" (margem-por-carteira.md §12.2 e §15), em dois modos:
 *
 * - **compacto** (painel): Credor/carteira, Receita, Custo total, Margem líquida e Tendência; os 10 primeiros credores
 *   na ordem da tabela, a linha "Outros (N credores)" com a soma do resto (as colunas fecham com o total), o aviso
 *   "Mostrando 10 de 30 credores…" e o link "Relatório completo" (nova aba, mesmos filtros, base e ordem). Sem busca,
 *   sem custo por categoria e sem "Mostrar mais": a altura do cartão fica contida e sem rolagem interna;
 * - **completo** (relatório): todas as colunas, custo por categoria, busca por credor ou carteira (`busca` na URL) e
 *   "Mostrar mais" (25 por vez, ux.md §21).
 *
 * Nos dois, credores expansíveis em carteiras, "Sem credor" e "Total" no rodapé, ordenação por coluna gravada pelo
 * host (`ordem` na URL), tendência de 6 meses e o drill-down de recuperado e receita para o Razão de comissão.
 */
@Component({
  selector: 'app-margin-table',
  imports: [Amount, DrillLink, Icon, InfoHint, NgTemplateOutlet, RouterLink, ShowMoreButton, TrendSparkline],
  templateUrl: './margin-table.html',
  styleUrl: './margin-table.scss',
})
export class MarginTable {
  readonly mode = input<MarginTableMode>('full');
  readonly report = input.required<MarginReport>();
  /** Tendência de 6 meses (pedido à parte): indefinida enquanto carrega. */
  readonly trend = input<MarginTrend | undefined>();
  readonly trendError = input(false);
  readonly filter = input.required<MarginFilter>();
  readonly basis = input.required<MarginBasis>();
  readonly sort = input.required<MarginSort>();
  readonly names = input.required<MarginNames>();
  /** Coluna escolhida (o host grava na URL). */
  readonly sortChange = output<MarginSort>();

  protected readonly full = computed(() => this.mode() === 'full');
  protected readonly categories = COST_CATEGORIES;
  protected readonly categoryLabels = CATEGORY_LABELS;
  protected readonly categoryShort = CATEGORY_SHORT_LABELS;
  protected readonly categoryInfo = CATEGORY_INFO;
  protected readonly rate = rateText;
  protected readonly negative = isNegative;
  protected readonly count = countText;

  /**
   * Colunas por categoria de custo: no relatório (o lugar de "todos os dados") começam ligadas; o switch esconde para
   * ler as margens sem rolagem lateral.
   */
  protected readonly withCategories = computed(() => this.full() && this.showCategories());

  protected readonly valueColumns = computed<readonly SortColumn[]>(() => (this.full() ? [RECOVERED, REVENUE] : [REVENUE]));
  protected readonly resultColumns = computed<readonly SortColumn[]>(() => (this.full() ? [TOTAL_COST, CONTRIBUTION, NET_MARGIN, ROI] : [TOTAL_COST, NET_MARGIN]));
  protected readonly columnCount = computed(
    () => 2 + this.valueColumns().length + (this.withCategories() ? this.categories.length + 1 : 0) + this.resultColumns().length,
  );
  protected readonly orderOptions = computed(() => ORDER_OPTIONS.filter((o) => this.full() || o.compact));

  protected creditorName(row: CreditorMargin): string {
    return this.names().creditorName(row);
  }

  protected portfolioName(row: PortfolioMargin): string {
    return this.names().portfolioName(row);
  }

  /** Margem com sinal, sem "(negativa)" (a palavra vai na linha de baixo, junto da taxa). */
  protected signedMoney(value: Parameters<typeof marginText>[0]): string {
    return marginText(value).replace(' (negativa)', '');
  }

  protected cost(line: MarginLine, category: CostCategory): string {
    return formatAmount(line.costs?.[category] ?? 0);
  }

  // ------------------------------------------------------------ ordenação
  /** Colunas do compacto: ordem por outra coluna (URL antiga, link do relatório) vale só no relatório. */
  private static readonly COMPACT_SORT_KEYS: readonly MarginSortKey[] = ['name', 'revenue', 'totalCost', 'netMargin'];

  /** Ordem aplicada na tela: no compacto, coluna que ele não mostra cai na ordem padrão (decisão 1365). */
  protected readonly effectiveSort = computed<MarginSort>(() => {
    const s = this.sort();
    return this.full() || MarginTable.COMPACT_SORT_KEYS.includes(s.key) ? s : DEFAULT_MARGIN_SORT;
  });

  protected orderValue(): string {
    const s = this.effectiveSort();
    return `${s.desc ? '-' : ''}${s.key}`;
  }

  protected order(value: string): void {
    const desc = value.startsWith('-');
    this.sortChange.emit({ key: (desc ? value.slice(1) : value) as MarginSortKey, desc });
  }

  protected sortBy(key: MarginSortKey): void {
    const current = this.effectiveSort();
    // primeira escolha de uma coluna de valor: maior primeiro; nome: A→Z
    this.sortChange.emit(current.key === key ? { key, desc: !current.desc } : { key, desc: key !== 'name' });
  }

  protected ariaSort(key: MarginSortKey): 'ascending' | 'descending' | null {
    const s = this.effectiveSort();
    return s.key === key ? (s.desc ? 'descending' : 'ascending') : null;
  }

  protected sortIcon(key: MarginSortKey): 'arrow-down-up' | 'arrow-down' | 'arrow-up' {
    const aria = this.ariaSort(key);
    return aria === null ? 'arrow-down-up' : aria === 'descending' ? 'arrow-down' : 'arrow-up';
  }

  protected readonly creditorRows = computed<CreditorItem[]>(() => {
    const sort = this.effectiveSort();
    return sortMarginRows(this.report().creditors ?? [], sort, (r) => this.creditorName(r)).map((row) => ({
      row,
      portfolios: sortMarginRows(row.portfolios ?? [], sort, (p) => this.portfolioName(p)),
    }));
  });

  // ------------------------------------------------------------ compacto: os 10 primeiros, "Outros" e o relatório
  private readonly cut = computed(() => compactCut(this.creditorRows(), MARGIN_COMPACT_LIMIT));
  /** Soma dos credores fora do corte (linha "Outros"): nula quando a lista coube inteira. */
  protected readonly others = computed(() => {
    const rest = this.cut().rest;
    return rest.length ? { count: rest.length, line: sumMarginLines(rest.map((i) => i.row)) } : null;
  });
  protected readonly notice = computed(() =>
    this.others()
      ? compactNotice(
          this.cut().visible.map((i) => i.row),
          this.creditorRows().map((i) => i.row),
        )
      : '',
  );
  protected readonly reportRoute = MARGIN_REPORT_ROUTE;
  protected readonly reportParams = computed(() => marginReportParams(this.filter(), this.basis(), this.sort()));

  // ------------------------------------------------------------ completo: busca por credor ou carteira e "Mostrar mais"
  private readonly route = inject(ActivatedRoute);
  private readonly urlWriter = injectUrlStateWriter();
  /** Custo por categoria no relatório: ligado por padrão; desligado fica na URL (`categorias=nao`). */
  protected readonly showCategories = signal(this.route.snapshot.queryParamMap.get(CATEGORIES_PARAM) !== 'nao');
  protected readonly canSearch = computed(() => this.full() && this.names().canSearch());
  /** O que está no campo (a cada tecla). */
  protected readonly searchInput = signal((this.route.snapshot.queryParamMap.get(SEARCH_PARAM) ?? '').slice(0, 80));
  /** Termo aplicado: o do campo depois da espera (ou na hora, ao limpar). */
  protected readonly searchApplied = signal(this.searchInput());
  protected readonly searchTerm = computed(() => (this.canSearch() ? normalizeSearch(this.searchApplied()) : ''));
  private readonly searchBox = viewChild<ElementRef<HTMLInputElement>>('searchBox');

  /**
   * Credores que casam com a busca pelo nome ou por uma carteira (ordem da tabela preservada), e os que entram pela
   * carteira (abertos já na busca). Sem busca, a lista inteira.
   */
  private readonly searchResult = computed(() => {
    const term = this.searchTerm();
    const all = this.creditorRows();
    if (!term) {
      return { rows: all, byPortfolio: new Set<string>() };
    }
    const rows: CreditorItem[] = [];
    const byPortfolio = new Set<string>();
    for (const item of all) {
      const portfolioHit = item.portfolios.some((p) => matchesSearch(this.portfolioName(p), term));
      if (portfolioHit || matchesSearch(this.creditorName(item.row), term)) {
        rows.push(item);
      }
      if (portfolioHit) {
        byPortfolio.add(item.row.creditorId);
      }
    }
    return { rows, byPortfolio };
  });
  private readonly searchExpanded = computed(() => this.searchResult().byPortfolio, {
    equal: (a, b) => a.size === b.size && [...a].every((id) => b.has(id)),
  });
  /** Credores abertos: os que a pessoa abriu e os que a busca encontrou pela carteira (que ela ainda pode fechar). */
  protected readonly expanded = linkedSignal<ReadonlySet<string>, ReadonlySet<string>>({
    source: this.searchExpanded,
    computation: (found, previous) => new Set([...(previous?.value ?? []), ...found]),
  });
  protected readonly searchCount = computed(() => this.searchResult().rows.length);

  /** Relatório: passos de 25; o corte vem depois da ordenação e da busca; filtro, base ou busca novos voltam a 25. */
  protected readonly table = showMore(() => this.searchResult().rows, {
    resetOn: () => [this.filter(), this.basis(), this.searchTerm()],
  });

  /** Credores na tela: os do corte no compacto; os do "Mostrar mais" no relatório. */
  protected readonly visibleRows = computed(() => (this.full() ? this.table.visible() : this.cut().visible));

  protected readonly searchMatch = (name: string) => !!this.searchTerm() && matchesSearch(name, this.searchTerm());

  protected search(value: string): void {
    this.searchInput.set(value.slice(0, 80));
  }

  protected clearSearch(): void {
    this.searchInput.set('');
    this.searchApplied.set('');
    this.searchBox()?.nativeElement.focus();
  }

  constructor() {
    toObservable(this.searchInput)
      .pipe(debounceTime(SEARCH_DEBOUNCE_MS), takeUntilDestroyed())
      .subscribe((value) => this.searchApplied.set(value));
    // termo na URL (`busca`, com replaceUrl e os demais parâmetros preservados), só no relatório: o "voltar" e o
    // recarregar mantêm a busca
    effect(() => {
      const term = this.searchApplied().trim();
      if (this.full()) {
        this.urlWriter.write({ [SEARCH_PARAM]: term || null });
      }
    });
    // categorias desligadas ficam na URL: recarregar, compartilhar e voltar do drill-down mantêm a escolha
    effect(() => {
      const show = this.showCategories();
      if (this.full()) {
        this.urlWriter.write({ [CATEGORIES_PARAM]: show ? null : 'nao' });
      }
    });
  }

  /** Credores na tela que têm carteira para abrir. */
  private readonly expandableIds = computed(() => this.visibleRows().filter((i) => i.portfolios.length).map((i) => i.row.creditorId));
  protected readonly expandable = computed(() => this.expandableIds().length > 0);
  protected readonly allOpen = computed(() => {
    const open = this.expanded();
    return this.expandable() && this.expandableIds().every((id) => open.has(id));
  });

  /** Anúncio ao leitor de tela depois de "Abrir/Fechar todas" (vazio até o primeiro clique). */
  protected readonly expandAllMessage = signal('');

  /** "Abrir todas as carteiras" abre as dos credores na tela; "Fechar todas" fecha tudo. */
  protected toggleAll(): void {
    if (this.allOpen()) {
      this.expanded.set(new Set());
      this.expandAllMessage.set('Carteiras fechadas.');
      return;
    }
    const open = new Set([...this.expanded(), ...this.expandableIds()]);
    this.expanded.set(open);
    const total = this.visibleRows().filter((i) => open.has(i.row.creditorId)).reduce((n, i) => n + i.portfolios.length, 0);
    this.expandAllMessage.set(`Carteiras abertas: ${total}.`);
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

  // ------------------------------------------------------------ tendência de 6 meses
  /** Pontos por credor (`creditorId`) e por carteira (`creditorId|portfolioId`; `-` = sem carteira). */
  private readonly trendIndex = computed(() => {
    const index = new Map<string, TrendPoint[]>();
    for (const c of this.trend()?.creditors ?? []) {
      index.set(c.creditorId, c.points);
      for (const p of c.portfolios ?? []) {
        index.set(`${c.creditorId}|${p.portfolioId ?? '-'}`, p.points);
      }
    }
    return index;
  });
  protected readonly trendMonths = computed(() => this.trend()?.months ?? []);

  /** Série da linha: `loading`/`error` enquanto a tendência não chega; sem fato nos 6 meses = todos os pontos vazios. */
  protected trendOf(creditorId: string, portfolioId?: string | null): TrendPoint[] | 'loading' | 'error' {
    if (this.trendError()) {
      return 'error';
    }
    if (!this.trend()) {
      return 'loading';
    }
    const key = portfolioId === undefined ? creditorId : `${creditorId}|${portfolioId ?? '-'}`;
    return this.trendIndex().get(key) ?? this.trendMonths().map((m) => ({ month: m.month, recovered: null, revenue: null, netMargin: null, netMarginRate: null }));
  }

  // ------------------------------------------------------------ drill-down: recuperado e receita → razão de comissão
  private readonly access = drillAccess();

  private ledger(filter: MarginFilter, label: string): DrillTarget | null {
    return this.access(ledgerTarget(asAnalyticsFilter(filter), {}, label));
  }

  protected readonly totalLink = computed(() => this.ledger(this.filter(), 'ver os recebimentos e a comissão no razão'));

  protected creditorLink(row: CreditorMargin): DrillTarget | null {
    return this.ledger({ ...this.filter(), creditorIds: [row.creditorId] }, 'ver os recebimentos e a comissão do credor no razão');
  }

  protected portfolioLink(creditor: CreditorMargin, row: PortfolioMargin): DrillTarget | null {
    return row.portfolioId
      ? this.ledger({ ...this.filter(), creditorIds: [creditor.creditorId], portfolioIds: [row.portfolioId] }, 'ver os recebimentos e a comissão da carteira no razão')
      : null;
  }
}
