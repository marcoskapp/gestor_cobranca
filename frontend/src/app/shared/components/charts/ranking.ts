import { ChartRow, ChartSeries } from './chart.models';
import { matchesSearch, normalizeSearch } from '../../util/text-search';

/** Substantivo do item do ranking, no singular e no plural (ex.: credor/credores). */
export interface RankNoun {
  one: string;
  many: string;
}

/** Chave da barra agregada "Outros" (nunca colide com ids, que são UUID ou códigos em maiúsculas). */
export const OTHERS_KEY = '__outros__';

export interface RankView {
  /** Barras do gráfico: os primeiros e, se houver, "Outros" por último. */
  bars: ChartRow[];
  others: ChartRow | null;
  /** Quantos itens o gráfico mostra individualmente. */
  shown: number;
  total: number;
  /** Aviso "Mostrando 10 de 47 credores…" (nulo quando todos aparecem). */
  notice: string | null;
}

const defaultFormat = (v: number) => v.toLocaleString('pt-BR', { maximumFractionDigits: 2 });

/**
 * Corte do ranking (ux.md §22): os {@code limit} primeiros na ordem recebida e uma barra "Outros" com a soma do resto,
 * sempre a última, para o gráfico fechar com o total. Sem "Outros" quando sobraria só 1 item (mostra-se o item) ou
 * quando o resto soma zero em todas as séries. O aviso diz quantos aparecem e, com {@code shareOf}, quanto os primeiros
 * somam da 1ª série — só sobre o que o usuário vê (o total é o das linhas recebidas, já no escopo dele).
 */
export function rankView(
  rows: readonly ChartRow[],
  series: readonly ChartSeries[],
  limit: number | null,
  noun: RankNoun,
  options: { shareOf?: string | null } = {},
): RankView {
  const total = rows.length;
  if (limit === null || total <= limit + 1) {
    return { bars: [...rows], others: null, shown: total, total, notice: null };
  }
  const head = rows.slice(0, limit);
  const rest = rows.slice(limit);
  const sums = series.map((_, i) => rest.reduce((s, r) => s + (r.values[i] ?? 0), 0));
  const others: ChartRow | null = rest.some((r) => r.values.some((v) => (v ?? 0) !== 0))
    ? {
        key: OTHERS_KEY,
        label: `Outros (${rest.length} ${rest.length === 1 ? noun.one : noun.many})`,
        values: sums,
        display: sums.map((s, i) => (series[i]?.format ?? defaultFormat)(s)),
      }
    : null;
  return {
    bars: others ? [...head, others] : head,
    others,
    shown: limit,
    total,
    notice: rankNotice(head, rest, limit, total, noun, options),
  };
}

function rankNotice(head: readonly ChartRow[], rest: readonly ChartRow[], limit: number, total: number, noun: RankNoun,
                    options: { shareOf?: string | null }): string {
  const headSum = head.reduce((s, r) => s + (r.values[0] ?? 0), 0);
  const all = headSum + rest.reduce((s, r) => s + (r.values[0] ?? 0), 0);
  const nonNegative = [...head, ...rest].every((r) => (r.values[0] ?? 0) >= 0);
  // curto (parecer de UX): "Mostrando 10 de 47 credores, que somam 71% da carteira."
  // nunca "100%" com "Outros" presente (arredondamento de 99,6%)
  const pct = Math.min(rest.some((r) => (r.values[0] ?? 0) > 0) ? 99 : 100, Math.round((headSum / all) * 100));
  const share = options.shareOf && all > 0 && nonNegative ? `, que somam ${pct}% ${options.shareOf}` : '';
  return `Mostrando ${limit} de ${total} ${noun.many}${share}.`;
}

/** Ordenação da lista completa: pelo nome ({@code -1}) ou por uma série; {@code desc} do maior para o menor. */
export interface RankSort {
  column: number;
  desc: boolean;
}

/** Lê a ordem da URL: `nome`, `-nome`, `1` (2ª série), `-0` (1ª série, decrescente). */
export function parseRankSort(value: string | null, seriesCount: number): RankSort | null {
  if (!value) {
    return null;
  }
  const desc = value.startsWith('-');
  const raw = desc ? value.slice(1) : value;
  if (raw === 'nome') {
    return { column: -1, desc };
  }
  const column = Number(raw);
  return Number.isInteger(column) && column >= 0 && column < seriesCount ? { column, desc } : null;
}

export function formatRankSort(sort: RankSort): string {
  return `${sort.desc ? '-' : ''}${sort.column < 0 ? 'nome' : sort.column}`;
}

/** Busca pelo nome e ordena (estável: empate mantém a ordem do critério do gráfico). */
export function rankTable(rows: readonly ChartRow[], query: string, sort: RankSort | null): ChartRow[] {
  const q = normalizeSearch(query);
  const found = q ? rows.filter((r) => matchesSearch(r.label, q)) : [...rows];
  if (!sort) {
    return found;
  }
  const dir = sort.desc ? -1 : 1;
  return found
    .map((r, i) => ({ r, i }))
    .sort((a, b) => {
      const c = sort.column < 0
        ? a.r.label.localeCompare(b.r.label, 'pt-BR', { sensitivity: 'base' })
        : (a.r.values[sort.column] ?? 0) - (b.r.values[sort.column] ?? 0);
      return c !== 0 ? c * dir : a.i - b.i;
    })
    .map((x) => x.r);
}

/** Somas de cada série (rodapé "Total" da lista completa). */
export function seriesTotals(rows: readonly ChartRow[], seriesCount: number): number[] {
  return Array.from({ length: seriesCount }, (_, i) => rows.reduce((s, r) => s + (r.values[i] ?? 0), 0));
}
