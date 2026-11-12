import { ParamMap, Params } from '@angular/router';
import { Permission } from '../../../core/auth/permission';
import { formatAmount, toAmount } from '../../../shared/components/amount/amount';
import { presetRange } from '../../analytics/data/analytics.filters';
import { AnalyticsFilter } from '../../analytics/data/analytics.models';
import { CATEGORY_LABELS, CostTab } from './costing.labels';
import { DIRECT_CATEGORIES, INDIRECT_CATEGORIES, MarginFilter, MarginLine, MarginWarning, Money } from './costing.models';

/**
 * Regras puras do painel de margem (sem Angular além dos tipos do roteador): período padrão, filtro na URL,
 * formatação de margem e taxas, tradução dos avisos da apuração, ordenação da tabela e passos da cascata.
 */

// ------------------------------------------------------------------ filtro na URL

/** Parâmetros na URL: os mesmos nomes dos painéis (`de`, `ate`, `credor`, `carteira`), para links entre eles. */
export const MARGIN_QUERY = { from: 'de', to: 'ate', creditor: 'credor', portfolio: 'carteira', sort: 'ordem' } as const;

const ISO = /^\d{4}-\d{2}-\d{2}$/;
const ID = /^[0-9a-f-]{36}$/i;

/** Período padrão: o último mês fechado (a apuração do mês corrente ainda muda todos os dias). */
export function lastClosedMonth(today: Date): { from: string; to: string } {
  return presetRange('previous-month', today);
}

/** Lê o filtro da URL descartando valores malformados (a URL é editável); sem período válido, o último mês fechado. */
export function parseMarginFilter(params: ParamMap, today: Date): MarginFilter {
  let from = params.get(MARGIN_QUERY.from) ?? '';
  let to = params.get(MARGIN_QUERY.to) ?? '';
  if (!ISO.test(from) || !ISO.test(to) || to < from) {
    ({ from, to } = lastClosedMonth(today));
  }
  const ids = (name: string) => params.getAll(name).filter((v) => ID.test(v)).slice(0, 50);
  return { from, to, creditorIds: ids(MARGIN_QUERY.creditor), portfolioIds: ids(MARGIN_QUERY.portfolio) };
}

// ------------------------------------------------------------------ reapuração manual

/** Maior período de uma reapuração pedida na tela (`app.costing.max-refresh-days`; o servidor confere). */
export const MAX_REFRESH_DAYS = 93;

/** Dia anterior a uma data `AAAA-MM-DD` (calendário puro, sem fuso). */
export function previousDay(iso: string): string {
  const [y, m, d] = iso.split('-').map(Number);
  return new Date(Date.UTC(y, m - 1, d - 1)).toISOString().slice(0, 10);
}

function daysBetween(from: string, to: string): number {
  const at = (iso: string) => {
    const [y, m, d] = iso.split('-').map(Number);
    return Date.UTC(y, m - 1, d);
  };
  return Math.round((at(to) - at(from)) / 86_400_000);
}

export type RefreshPeriod = { ok: true; from: string; to: string } | { ok: false; message: string };

/**
 * Período da reapuração a partir do filtro: vai até ontem (o dia de hoje ainda não fechou; o fim do filtro é
 * cortado) e tem no máximo {@link MAX_REFRESH_DAYS} dias. `today` é a data de negócio (`zonedNow().date`).
 */
export function refreshPeriod(filter: Pick<MarginFilter, 'from' | 'to'>, today: string): RefreshPeriod {
  const yesterday = previousDay(today);
  const to = filter.to < yesterday ? filter.to : yesterday;
  if (filter.from > to) {
    return { ok: false, message: 'Não há dia fechado no período para reapurar: a reapuração vai até ontem.' };
  }
  if (daysBetween(filter.from, to) >= MAX_REFRESH_DAYS) {
    return { ok: false, message: `Reapure no máximo ${MAX_REFRESH_DAYS} dias por vez: escolha um período menor no filtro.` };
  }
  return { ok: true, from: filter.from, to };
}

/** Parâmetros de URL do filtro (listas vazias somem). */
export function marginFilterParams(filter: MarginFilter): Params {
  return {
    [MARGIN_QUERY.from]: filter.from,
    [MARGIN_QUERY.to]: filter.to,
    [MARGIN_QUERY.creditor]: filter.creditorIds.length ? filter.creditorIds : null,
    [MARGIN_QUERY.portfolio]: filter.portfolioIds.length ? filter.portfolioIds : null,
  };
}

/** O filtro no formato da barra de filtros e do drill-down dos painéis (sem pessoa, equipe nem canal). */
export function asAnalyticsFilter(filter: MarginFilter): AnalyticsFilter {
  return { ...filter, operatorIds: [], teamIds: [], channels: [], groupBy: null };
}

export function fromAnalyticsFilter(filter: AnalyticsFilter): MarginFilter {
  return { from: filter.from, to: filter.to, creditorIds: filter.creditorIds, portfolioIds: filter.portfolioIds };
}

export function sameMarginFilter(a: MarginFilter, b: MarginFilter): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}

// ------------------------------------------------------------------ formatação

const PERCENT = new Intl.NumberFormat('pt-BR', { style: 'percent', minimumFractionDigits: 1, maximumFractionDigits: 1 });
const HOURS = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 1 });
const INTEGER = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 0 });

export function num(value: Money | null | undefined): number {
  return toAmount(value) ?? 0;
}

export function isNegative(value: Money | null | undefined): boolean {
  return (toAmount(value) ?? 0) < 0;
}

/** Taxa (fração) como percentual; sem base → "—". */
export function rateText(value: Money | null | undefined): string {
  const n = toAmount(value);
  return n === null ? '—' : PERCENT.format(n);
}

/**
 * Margem em reais com o sinal escrito e, quando negativa, a palavra "negativa" (a cor nunca é o único sinal — WCAG
 * 1.4.1): "R$ 1.200,00", "−R$ 350,00 (negativa)".
 */
export function marginText(value: Money | null | undefined): string {
  const n = toAmount(value);
  if (n === null) {
    return '—';
  }
  return n < 0 ? `−${formatAmount(-n)} (negativa)` : formatAmount(n);
}

/** Margem compacta para cartões e gráficos ("R$ 1,25 mi", "−R$ 350,00"). */
export function marginShort(value: Money | null | undefined): string {
  const n = toAmount(value);
  if (n === null) {
    return '—';
  }
  return n < 0 ? `−${formatAmount(-n, true)}` : formatAmount(n, true);
}

export function hoursText(value: Money | null | undefined): string {
  const n = toAmount(value);
  return n === null ? '—' : `${HOURS.format(n)} h`;
}

export function countText(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : INTEGER.format(value);
}

/** Mês do servidor (`AAAA-MM` ou `AAAA-MM-01`) por extenso curto: "out. de 2026". */
export function monthLabel(month: string): string {
  const match = /^(\d{4})-(\d{2})/.exec(month);
  if (!match) {
    return month;
  }
  const date = new Date(Number(match[1]), Number(match[2]) - 1, 1);
  return new Intl.DateTimeFormat('pt-BR', { month: 'short', year: 'numeric' }).format(date);
}

// ------------------------------------------------------------------ avisos da apuração

/** Aviso traduzido: o que aconteceu, o efeito no número e o próximo passo, com o atalho para a configuração. */
export interface WarningView {
  code: string;
  text: string;
  /** Aba da configuração de custos onde se corrige. */
  tab: CostTab | null;
  linkLabel: string | null;
  /** Permissões da aba de destino (o atalho só aparece para quem pode corrigir). */
  permissions: readonly string[];
  queryParams: Record<string, string> | null;
}

const plural = (n: number, one: string, many: string) => (n === 1 ? one : many);

export function warningView(warning: MarginWarning): WarningView {
  const n = warning.count;
  const qty = countText(n);
  switch (warning.code) {
    case 'HOURS_WITHOUT_BAND':
      return {
        code: warning.code,
        text: `${HOURS.format(n)} ${plural(n, 'hora', 'horas')} de trabalho sem faixa de custo: ${plural(n, 'entrou', 'entraram')} com custo zero, e a margem aparece maior do que é. Atribua uma faixa a cada pessoa a partir da data em que ela começou.`,
        tab: 'faixas',
        linkLabel: 'Atribuir faixas de custo',
        permissions: [Permission.COST_RATE_MANAGE],
        queryParams: { aba: 'faixas' },
      };
    case 'MISSING_FX_RATE':
      return {
        code: warning.code,
        text: `${qty} ${plural(n, 'mês', 'meses')} sem câmbio do dólar informado: o custo de inteligência artificial usou o último câmbio informado (ou ficou zerado, se não há nenhum). Informe o câmbio de cada mês.`,
        tab: 'cambio',
        linkLabel: 'Informar o câmbio',
        permissions: [Permission.COST_MANAGE],
        queryParams: { aba: 'cambio' },
      };
    case 'NO_FX_RATE':
      return {
        code: warning.code,
        text: 'Nenhum câmbio do dólar informado: o custo de inteligência artificial ficou zerado. Informe o câmbio do mês.',
        tab: 'cambio',
        linkLabel: 'Informar o câmbio',
        permissions: [Permission.COST_MANAGE],
        queryParams: { aba: 'cambio' },
      };
    case 'OVERHEAD_WITHOUT_BASIS':
      return {
        code: warning.code,
        text: `${qty} ${plural(n, 'custo fixo ficou', 'custos fixos ficaram')} sem base de rateio no mês (nenhuma hora, recebimento ou caso): ${plural(n, 'aparece', 'aparecem')} em "Sem credor". Confira a regra de rateio.`,
        tab: 'fixos',
        linkLabel: 'Ver custos fixos',
        permissions: [Permission.COST_MANAGE],
        queryParams: { aba: 'fixos' },
      };
    case 'NOT_REFRESHED':
      return {
        code: warning.code,
        text: `${qty} ${plural(n, 'dia do período ainda não foi apurado', 'dias do período ainda não foram apurados')}: a apuração roda toda madrugada. Os números desses dias aparecem depois dela, ou reapure o período.`,
        tab: null,
        linkLabel: null,
        permissions: [],
        queryParams: null,
      };
    case 'MISSING_TARIFF':
      return {
        code: warning.code,
        text: `${qty} ${plural(n, 'unidade', 'unidades')} de uso sem tarifa (minutos de ligação, conversas de WhatsApp ou mensagens): ${plural(n, 'entrou', 'entraram')} com custo zero. Cadastre a tarifa de cada canal usado (a de telefonia fica na conta do provedor).`,
        tab: 'tarifas',
        linkLabel: 'Cadastrar tarifas de canal',
        permissions: [Permission.COST_MANAGE],
        queryParams: { aba: 'tarifas' },
      };
    default:
      return {
        code: warning.code,
        text: `A apuração encontrou ${qty} ${plural(n, 'registro', 'registros')} sem o custo completo. Confira os cadastros de custo.`,
        tab: null,
        linkLabel: 'Ver custos operacionais',
        permissions: [Permission.COST_MANAGE, Permission.COST_RATE_MANAGE],
        queryParams: null,
      };
  }
}

/** Avisos com ocorrência (contagem zero não aparece). */
export function warningViews(warnings: readonly MarginWarning[] | null | undefined): WarningView[] {
  return (warnings ?? []).filter((w) => w.count > 0).map(warningView);
}

// ------------------------------------------------------------------ ordenação

export type MarginSortKey =
  | 'name'
  | 'recovered'
  | 'revenue'
  | 'directCost'
  | 'totalCost'
  | 'contributionMargin'
  | 'netMargin'
  | 'netMarginRate'
  | 'roi';

export const MARGIN_SORT_KEYS: readonly MarginSortKey[] = [
  'name',
  'recovered',
  'revenue',
  'directCost',
  'totalCost',
  'contributionMargin',
  'netMargin',
  'netMarginRate',
  'roi',
];

export interface MarginSort {
  key: MarginSortKey;
  desc: boolean;
}

/** Padrão: menor margem líquida primeiro (o que consome margem aparece no topo). */
export const DEFAULT_MARGIN_SORT: MarginSort = { key: 'netMargin', desc: false };

/** `ordem=-netMargin` (decrescente) ou `ordem=revenue`; inválido → padrão. */
export function parseMarginSort(raw: string | null): MarginSort {
  if (!raw) {
    return DEFAULT_MARGIN_SORT;
  }
  const desc = raw.startsWith('-');
  const key = (desc ? raw.slice(1) : raw) as MarginSortKey;
  return MARGIN_SORT_KEYS.includes(key) ? { key, desc } : DEFAULT_MARGIN_SORT;
}

export function formatMarginSort(sort: MarginSort): string | null {
  return sort.key === DEFAULT_MARGIN_SORT.key && sort.desc === DEFAULT_MARGIN_SORT.desc ? null : `${sort.desc ? '-' : ''}${sort.key}`;
}

/**
 * Ordena linhas pela coluna. Taxa sem base (nula) fica sempre no fim, em qualquer direção (não é "zero"); empate
 * desempata pelo nome.
 */
export function sortMarginRows<T extends MarginLine>(rows: readonly T[], sort: MarginSort, nameOf: (row: T) => string): T[] {
  const dir = sort.desc ? -1 : 1;
  const byName = (a: T, b: T) => nameOf(a).localeCompare(nameOf(b), 'pt-BR');
  return [...rows].sort((a, b) => {
    if (sort.key === 'name') {
      return dir * byName(a, b);
    }
    const va = toAmount(a[sort.key] as Money | null);
    const vb = toAmount(b[sort.key] as Money | null);
    if (va === null || vb === null) {
      return va === vb ? byName(a, b) : va === null ? 1 : -1;
    }
    return va === vb ? byName(a, b) : dir * (va - vb);
  });
}

// ------------------------------------------------------------------ cascata

export type WaterfallKind = 'start' | 'cost' | 'subtotal';

/** Passo da cascata: a barra vai de `start` a `end` (subtotais partem do zero). */
export interface WaterfallStep {
  key: string;
  label: string;
  kind: WaterfallKind;
  start: number;
  end: number;
  /** Valor do passo com sinal escrito ("−R$ 1.200,00"). */
  display: string;
}

/**
 * Receita → custos diretos por categoria → margem de contribuição → ociosidade e custos fixos → margem líquida
 * (decisão do responsável, Q2: a ociosidade fica fora da margem de contribuição).
 */
export function waterfallSteps(line: MarginLine): WaterfallStep[] {
  const steps: WaterfallStep[] = [];
  const revenue = num(line.revenue);
  steps.push({ key: 'revenue', label: 'Receita', kind: 'start', start: 0, end: revenue, display: formatAmount(revenue) });
  let running = revenue;
  const cost = (category: (typeof DIRECT_CATEGORIES)[number]) => {
    const value = num(line.costs?.[category]);
    steps.push({ key: category, label: CATEGORY_LABELS[category], kind: 'cost', start: running, end: running - value, display: `−${formatAmount(value)}` });
    running -= value;
  };
  DIRECT_CATEGORIES.forEach(cost);
  const contribution = num(line.contributionMargin);
  steps.push({ key: 'contribution', label: 'Margem de contribuição', kind: 'subtotal', start: 0, end: contribution, display: marginText(contribution) });
  running = contribution;
  INDIRECT_CATEGORIES.forEach(cost);
  const net = num(line.netMargin);
  steps.push({ key: 'net', label: 'Margem líquida', kind: 'subtotal', start: 0, end: net, display: marginText(net) });
  return steps;
}
