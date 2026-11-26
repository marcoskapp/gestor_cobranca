import { formatAmount } from '../../shared/components/amount/amount';
import { ChartRow } from '../../shared/components/charts/chart.models';
import { CumulativeRow } from '../../shared/components/charts/cumulative-chart';
import {
  CreditorAgreements,
  CreditorDailyPoint,
  CreditorMonthlyRecovery,
  CreditorPeriod,
  CreditorPortfolioShare,
  CreditorRecovery,
} from './creditor.models';

/**
 * Textos e linhas dos indicadores do Início do portal do credor (fatia 7), a partir de `GET /overview`. Funções puras:
 * a tela só escolhe o estado do cartão. Nunca "−R$": valores de repasse vão sempre positivos, com quem paga quem escrito.
 */

/** Opções do seletor de período, com o valor curto em português da URL (`?periodo=`). */
export const CREDITOR_PERIODS: readonly { value: CreditorPeriod; param: string; label: string }[] = [
  { value: 'THIS_MONTH', param: 'este-mes', label: 'Este mês' },
  { value: 'LAST_MONTH', param: 'mes-passado', label: 'Mês passado' },
  { value: 'LAST_3_MONTHS', param: 'ultimos-3-meses', label: 'Últimos 3 meses' },
  { value: 'THIS_YEAR', param: 'este-ano', label: 'Este ano' },
];
export const DEFAULT_PERIOD_PARAM = 'este-mes';
export const PERIOD_PARAMS = CREDITOR_PERIODS.map((p) => p.param);

export function periodOf(param: string): CreditorPeriod {
  return CREDITOR_PERIODS.find((p) => p.param === param)?.value ?? 'THIS_MONTH';
}

/** Limite do alerta de "Acordos em dia" (o servidor marca `alert` abaixo dele). */
export const AGREEMENTS_ALERT_RATIO = 0.8;

const COUNT = new Intl.NumberFormat('pt-BR');
const PERCENT = new Intl.NumberFormat('pt-BR', { style: 'percent', maximumFractionDigits: 1 });

/**
 * "82%" (ou "79,9%"): truncado na primeira casa, para que um valor em alerta (abaixo de 80%) nunca apareça arredondado
 * para "80%".
 */
export function ratioText(ratio: number): string {
  return PERCENT.format(Math.floor(Number(ratio) * 1000) / 1000);
}

/** Valor do cartão "Acordos em dia": porcentagem, ou texto quando não há acordo em vigor (nunca "0%"). */
export function agreementsValue(a: CreditorAgreements): string {
  return a.ratio === null || a.active === 0 ? 'Nenhum acordo em vigor' : ratioText(a.ratio);
}

/** Base do cartão e, em alerta, o motivo escrito (a cor nunca comunica sozinha). */
export function agreementsDetail(a: CreditorAgreements): string {
  if (a.ratio === null || a.active === 0) {
    return `Sem acordo formalizado ou em pagamento em ${dayMonthYear(a.referenceDate)}`;
  }
  const when = a.referenceDate ? ` em ${dayMonthYear(a.referenceDate)}` : '';
  const base = `${COUNT.format(a.onTrack)} de ${COUNT.format(a.active)} acordos em vigor${when}`;
  if (!a.alert) {
    return base;
  }
  const late = a.active - a.onTrack;
  return `${base}. Abaixo de 80%: ${COUNT.format(late)} ${late === 1 ? 'acordo tem' : 'acordos têm'} parcela vencida e não paga`;
}

/** Variação do recuperado sobre o período anterior, com sinal e texto. */
export function variationText(current: number, previous: number): string {
  const cur = Number(current);
  const prev = Number(previous);
  if (prev <= 0) {
    return cur > 0 ? 'Sem recuperado no período anterior para comparar' : 'Nada recuperado neste período nem no anterior';
  }
  const change = (cur - prev) / prev;
  const rounded = Math.round(change * 1000) / 1000;
  if (rounded === 0) {
    return 'Igual ao período anterior';
  }
  const text = PERCENT.format(Math.abs(rounded));
  return rounded > 0 ? `+${text} sobre o período anterior` : `−${text} sobre o período anterior`;
}

/** Detalhe de "Recuperado no período": a variação e a parte paga direto a você. */
export function recoveryDetail(r: CreditorRecovery): string {
  const variation = variationText(r.recovered, r.previousRecovered);
  const paid = Number(r.paidToCreditor);
  return paid > 0 ? `${variation}. Pago direto a você: ${formatAmount(paid)}` : variation;
}

const MONTH_SHORT = new Intl.DateTimeFormat('pt-BR', { month: 'short' });
const MONTH_LONG = new Intl.DateTimeFormat('pt-BR', { month: 'long', year: 'numeric' });

function monthDate(month: string): Date {
  const [y, m] = month.split('-').map(Number);
  return new Date(y, m - 1, 1);
}

/** "nov/26" */
export function monthLabel(month: string): string {
  const d = monthDate(month);
  return `${MONTH_SHORT.format(d).replace('.', '')}/${String(d.getFullYear()).slice(-2)}`;
}

/** "novembro de 2026" */
export function monthName(month: string): string {
  return MONTH_LONG.format(monthDate(month));
}

/** Colunas da série mensal (sempre 6 meses, do mais antigo ao corrente). */
export function monthlyRows(months: readonly CreditorMonthlyRecovery[]): ChartRow[] {
  return [...months]
    .sort((a, b) => a.month.localeCompare(b.month))
    .map((m) => ({
      key: m.month,
      label: monthLabel(m.month),
      values: [Math.max(0, Number(m.recovered))],
      display: [formatAmount(m.recovered)],
      note: Number(m.paidToCreditor) > 0 ? `${formatAmount(m.paidToCreditor)} pago direto a você` : undefined,
    }));
}

export function monthlySummary(months: readonly CreditorMonthlyRecovery[]): string {
  const sorted = [...months].sort((a, b) => a.month.localeCompare(b.month));
  if (!sorted.length) {
    return 'Recuperado de cada um dos últimos 6 meses.';
  }
  const total = sorted.reduce((sum, m) => sum + Number(m.recovered), 0);
  if (total <= 0) {
    return 'Nada recuperado nos últimos 6 meses.';
  }
  const current = sorted.at(-1)!;
  const best = sorted.reduce((a, b) => (Number(b.recovered) > Number(a.recovered) ? b : a));
  return `Recuperado nos últimos 6 meses: ${formatAmount(total)} no total. Em ${monthName(current.month)}, até agora, `
    + `${formatAmount(current.recovered)}; o melhor mês foi ${monthName(best.month)}, com ${formatAmount(best.recovered)}.`;
}

/** Barras do recuperado por carteira: as 5 com mais recuperado e "Outros" por último. */
export function portfolioRows(shares: readonly CreditorPortfolioShare[]): ChartRow[] {
  const named = shares.filter((s) => !s.others).sort((a, b) => Number(b.recovered) - Number(a.recovered));
  const others = shares.filter((s) => s.others && Number(s.recovered) > 0);
  return [...named, ...others].map((s, i) => ({
    key: s.others ? 'others' : (s.portfolioId ?? `p${i}`),
    label: s.others ? 'Outros' : (s.name ?? 'Carteira sem nome'),
    values: [Math.max(0, Number(s.recovered))],
    display: [formatAmount(s.recovered)],
    note: s.others ? 'Demais carteiras e recebimentos sem carteira' : undefined,
  }));
}

export function portfolioSummary(shares: readonly CreditorPortfolioShare[]): string {
  const rows = portfolioRows(shares);
  const total = rows.reduce((sum, r) => sum + r.values[0], 0);
  if (!rows.length || total <= 0) {
    return 'Recuperado no período escolhido, por carteira.';
  }
  const top = rows[0];
  return `Recuperado no período por carteira: ${formatAmount(total)} no total; a maior parte veio de ${top.label}, com `
    + `${top.display[0]} (${PERCENT.format(Math.floor((top.values[0] / total) * 1000) / 1000)}).`;
}

/** Pontos do acumulado por dia: período em aberto e o anterior no mesmo dia. */
export function cumulativeRows(daily: readonly CreditorDailyPoint[], previous: readonly CreditorDailyPoint[]): CumulativeRow[] {
  const days = Math.max(0, ...daily.map((d) => d.day), ...previous.map((d) => d.day));
  const current = new Map(daily.map((d) => [d.day, Number(d.recovered)]));
  const before = new Map(previous.map((d) => [d.day, Number(d.recovered)]));
  return Array.from({ length: days }, (_, i) => i + 1).map((day) => ({
    day,
    current: current.get(day) ?? null,
    previous: before.get(day) ?? null,
  }));
}

export function cumulativeSummary(daily: readonly CreditorDailyPoint[], previous: readonly CreditorDailyPoint[]): string {
  const last = [...daily].sort((a, b) => a.day - b.day).at(-1);
  if (!last) {
    return 'Recuperado acumulado dia a dia no período em aberto, comparado com o período anterior.';
  }
  const same = previous.find((p) => p.day === last.day);
  const base = `Até o dia ${last.day} do período em aberto, ${formatAmount(last.recovered)} recuperado`;
  return same ? `${base}; no mesmo dia do período anterior, ${formatAmount(same.recovered)}.` : `${base}.`;
}

/** "dd/mm/aaaa" de uma data (yyyy-MM-dd) ou de um instante (no fuso do navegador). */
export function dayMonthYear(value: string | null | undefined): string {
  if (!value) {
    return '';
  }
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    const [y, m, d] = value.split('-');
    return `${d}/${m}/${y}`;
  }
  return new Date(value).toLocaleDateString('pt-BR');
}
