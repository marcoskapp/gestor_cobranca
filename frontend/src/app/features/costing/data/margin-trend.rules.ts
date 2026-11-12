import { toAmount } from '../../../shared/components/amount/amount';
import { TrendMonth, TrendPoint } from './costing.models';

/**
 * Regras puras da tendência de 6 meses por credor e carteira (margem-por-carteira.md §14.1): a frase acessível da
 * coluna "Tendência" e a geometria do minigráfico. A série é a margem líquida em % da receita (comparável entre
 * credores de tamanhos diferentes); mês sem receita ou sem apuração é lacuna, nunca zero.
 */

const PERCENT = new Intl.NumberFormat('pt-BR', { style: 'percent', minimumFractionDigits: 1, maximumFractionDigits: 1 });
/** Variação abaixo de 1 ponto percentual entre o primeiro e o último mês com base = "estável". */
export const TREND_STABLE_POINTS = 0.01;

/** Mês `AAAA-MM` abreviado ("jun."), com o ano quando pedido ("jun. de 2026"). */
export function shortMonth(month: string, withYear = false): string {
  const match = /^(\d{4})-(\d{2})/.exec(month);
  if (!match) {
    return month;
  }
  const date = new Date(Number(match[1]), Number(match[2]) - 1, 1);
  return new Intl.DateTimeFormat('pt-BR', withYear ? { month: 'short', year: 'numeric' } : { month: 'short' }).format(date);
}

function percent(value: number): string {
  return `${PERCENT.format(value)}${value < 0 ? ' (negativa)' : ''}`.replace(/ /g, ' ');
}

const plural = (n: number, one: string, many: string) => `${n} ${n === 1 ? one : many}`;

/** Taxa do ponto (fração) ou nula sem base. */
export function pointRate(point: TrendPoint | null | undefined): number | null {
  return toAmount(point?.netMarginRate ?? null);
}

/**
 * Frase da tendência para leitor de tela e dica: "Margem líquida de jun. a nov.: subiu de 8,0% para 12,0% da
 * receita. 1 mês sem receita." Compara o primeiro e o último mês com base; variação menor que 1 ponto = estável.
 */
export function trendText(points: readonly TrendPoint[], months: readonly TrendMonth[]): string {
  if (!months.length) {
    return 'Tendência indisponível.';
  }
  const first = months[0].month;
  const last = months[months.length - 1].month;
  const sameYear = first.slice(0, 4) === last.slice(0, 4);
  const range = `de ${shortMonth(first, !sameYear)} a ${shortMonth(last, !sameYear)}`;
  const valid = points.map((p, i) => ({ month: months[i]?.month ?? p.month, rate: pointRate(p) })).filter((p) => p.rate !== null) as {
    month: string;
    rate: number;
  }[];
  let main: string;
  if (valid.length === 0) {
    main = `Margem líquida ${range}: sem receita nos meses apurados`;
  } else if (valid.length === 1) {
    main = `Margem líquida ${range}: só ${shortMonth(valid[0].month)} tem receita, com ${percent(valid[0].rate)} da receita`;
  } else {
    const a = valid[0].rate;
    const b = valid[valid.length - 1].rate;
    if (Math.abs(b - a) < TREND_STABLE_POINTS) {
      main = `Margem líquida ${range}: estável em ${percent(b)} da receita`;
    } else {
      main = `Margem líquida ${range}: ${b > a ? 'subiu' : 'caiu'} de ${percent(a)} para ${percent(b)} da receita`;
    }
  }
  const notes: string[] = [];
  const notRefreshed = months.filter((m) => m.status === 'NOT_REFRESHED').map((m) => shortMonth(m.month));
  const noBase = points.filter((p, i) => pointRate(p) === null && months[i]?.status !== 'NOT_REFRESHED').length;
  if (noBase > 0 && valid.length > 1) {
    notes.push(`${plural(noBase, 'mês', 'meses')} sem receita`);
  }
  if (notRefreshed.length) {
    notes.push(`${notRefreshed.join(', ')} ainda não ${notRefreshed.length === 1 ? 'apurado' : 'apurados'}`);
  }
  const partial = months.find((m) => m.status === 'PARTIAL');
  if (partial) {
    notes.push(`${shortMonth(partial.month)} parcial`);
  }
  return `${main}.${notes.length ? ` ${notes.join('; ')}.` : ''}`;
}

/** Linha do mês para a dica da tendência: "out.: 12,0% da receita" ou "out.: sem receita". */
export function trendPointText(point: TrendPoint, month: TrendMonth): string {
  if (month.status === 'NOT_REFRESHED') {
    return `${shortMonth(month.month)}: não apurado`;
  }
  const rate = pointRate(point);
  return `${shortMonth(month.month)}: ${rate === null ? 'sem receita' : `${percent(rate)} da receita`}${month.status === 'PARTIAL' ? ' (parcial)' : ''}`;
}

// ------------------------------------------------------------------ minigráfico

export interface SparkDot {
  x: number;
  y: number;
  negative: boolean;
  last: boolean;
}

export interface Sparkline {
  /** Trechos contínuos (o mês sem base interrompe a linha). */
  paths: string[];
  dots: SparkDot[];
  /** Linha do zero quando a série cruza ou toca o zero. */
  zeroY: number | null;
}

/**
 * Geometria do minigráfico (largura × altura em px, margem interna `pad`): x igualmente espaçado pelos meses, y linear
 * entre o menor e o maior valor da série (com o zero quando a série é negativa em algum mês).
 */
export function sparkline(points: readonly TrendPoint[], width: number, height: number, pad = 3): Sparkline {
  const rates = points.map(pointRate);
  const values = rates.filter((r): r is number => r !== null);
  if (!values.length) {
    return { paths: [], dots: [], zeroY: null };
  }
  const hasNegative = values.some((v) => v < 0);
  let lo = Math.min(...values, ...(hasNegative ? [0] : []));
  let hi = Math.max(...values, ...(hasNegative ? [0] : []));
  if (hi - lo < 0.02) {
    // série quase plana: faixa mínima de 2 pontos percentuais, centrada no valor
    const mid = (hi + lo) / 2;
    lo = mid - 0.01;
    hi = mid + 0.01;
  }
  const step = points.length > 1 ? (width - 2 * pad) / (points.length - 1) : 0;
  const x = (i: number) => pad + i * step;
  const y = (v: number) => pad + (1 - (v - lo) / (hi - lo)) * (height - 2 * pad);
  const paths: string[] = [];
  let current = '';
  const lastIndex = rates.reduce<number>((acc, r, i) => (r === null ? acc : i), -1);
  const dots: SparkDot[] = [];
  rates.forEach((r, i) => {
    if (r === null) {
      if (current) {
        paths.push(current);
      }
      current = '';
      return;
    }
    const px = round(x(i));
    const py = round(y(r));
    current += `${current ? 'L' : 'M'}${px},${py}`;
    dots.push({ x: px, y: py, negative: r < 0, last: i === lastIndex });
  });
  if (current) {
    paths.push(current);
  }
  return { paths: paths.filter((p) => p.includes('L')), dots, zeroY: hasNegative ? round(y(0)) : null };
}

const round = (n: number) => Math.round(n * 10) / 10;
