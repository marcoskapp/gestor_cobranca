import { ParamMap, Params } from '@angular/router';
import { AnalyticsFilter } from './analytics.models';

/** Nomes dos parâmetros na URL (filtros persistidos e compartilháveis). */
export const QUERY = {
  from: 'de',
  to: 'ate',
  creditor: 'credor',
  portfolio: 'carteira',
  operator: 'operador',
  team: 'equipe',
  channel: 'canal',
  groupBy: 'agrupar',
} as const;

export type PeriodPreset = '7d' | '30d' | '90d' | 'month' | 'previous-month' | 'year';

const ISO = /^\d{4}-\d{2}-\d{2}$/;
const ID = /^[0-9a-f-]{36}$/i;
const CODE = /^[A-Z_]{1,20}$/;

export function isoDate(date: Date): string {
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  return `${y}-${m}-${d}`;
}

function addDays(date: Date, n: number): Date {
  const copy = new Date(date);
  copy.setDate(copy.getDate() + n);
  return copy;
}

/** Período de um atalho, inclusive nas duas pontas, a partir de `today`. */
export function presetRange(preset: PeriodPreset, today: Date): { from: string; to: string } {
  switch (preset) {
    case '7d':
      return { from: isoDate(addDays(today, -6)), to: isoDate(today) };
    case '90d':
      return { from: isoDate(addDays(today, -89)), to: isoDate(today) };
    case 'month':
      return { from: isoDate(new Date(today.getFullYear(), today.getMonth(), 1)), to: isoDate(today) };
    case 'previous-month':
      return {
        from: isoDate(new Date(today.getFullYear(), today.getMonth() - 1, 1)),
        to: isoDate(new Date(today.getFullYear(), today.getMonth(), 0)),
      };
    case 'year':
      return { from: isoDate(new Date(today.getFullYear(), 0, 1)), to: isoDate(today) };
    default:
      return { from: isoDate(addDays(today, -29)), to: isoDate(today) };
  }
}

/** Atalho que corresponde ao período (ou `custom`). */
export function presetOf(from: string, to: string, today: Date): PeriodPreset | 'custom' {
  const presets: PeriodPreset[] = ['7d', '30d', '90d', 'month', 'previous-month', 'year'];
  return presets.find((p) => {
    const r = presetRange(p, today);
    return r.from === from && r.to === to;
  }) ?? 'custom';
}

/**
 * Lê os filtros da URL, descartando valores malformados (a URL é editável). Sem período válido,
 * vale "últimos 30 dias"; `groupBy` só se estiver entre os permitidos da tela.
 */
export function parseFilter(params: ParamMap, today: Date, allowedGroupBy: readonly string[], defaultGroupBy: string | null): AnalyticsFilter {
  const fallback = presetRange('30d', today);
  let from = params.get(QUERY.from) ?? '';
  let to = params.get(QUERY.to) ?? '';
  if (!ISO.test(from) || !ISO.test(to) || to < from) {
    from = fallback.from;
    to = fallback.to;
  }
  const ids = (name: string) => params.getAll(name).filter((v) => ID.test(v)).slice(0, 50);
  const groupBy = params.get(QUERY.groupBy);
  return {
    from,
    to,
    creditorIds: ids(QUERY.creditor),
    portfolioIds: ids(QUERY.portfolio),
    operatorIds: ids(QUERY.operator),
    teamIds: ids(QUERY.team),
    channels: params.getAll(QUERY.channel).filter((v) => CODE.test(v)).slice(0, 10),
    groupBy: groupBy && allowedGroupBy.includes(groupBy) ? groupBy : defaultGroupBy,
  };
}

/** Parâmetros de URL do filtro (listas vazias somem da URL). */
export function filterParams(filter: AnalyticsFilter): Params {
  const list = (values: string[]) => (values.length ? values : null);
  return {
    [QUERY.from]: filter.from,
    [QUERY.to]: filter.to,
    [QUERY.creditor]: list(filter.creditorIds),
    [QUERY.portfolio]: list(filter.portfolioIds),
    [QUERY.operator]: list(filter.operatorIds),
    [QUERY.team]: list(filter.teamIds),
    [QUERY.channel]: list(filter.channels),
    [QUERY.groupBy]: filter.groupBy,
  };
}

export function sameFilter(a: AnalyticsFilter, b: AnalyticsFilter): boolean {
  return JSON.stringify(a) === JSON.stringify(b);
}
