import { formatAmount, toAmount } from '../../../shared/components/amount/amount';
import { UNAVAILABLE_LABELS } from './analytics.labels';
import { Money, Ratio } from './analytics.models';

const PERCENT = new Intl.NumberFormat('pt-BR', { style: 'percent', minimumFractionDigits: 1, maximumFractionDigits: 1 });
const INTEGER = new Intl.NumberFormat('pt-BR', { maximumFractionDigits: 0 });
const DECIMAL = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 1, maximumFractionDigits: 2 });
const FIXED2 = new Intl.NumberFormat('pt-BR', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

export function num(value: Money | null | undefined): number {
  return toAmount(value) ?? 0;
}

export function count(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : INTEGER.format(value);
}

export function decimal(value: Money | null | undefined): string {
  const n = toAmount(value);
  return n === null ? '—' : DECIMAL.format(n);
}

/** Taxa como percentual (a API devolve fração); indisponível vira "—". */
/** Taxa por hora e similares com 2 casas fixas (colunas alinhadas). */
export function fixed2(value: Money | null | undefined): string {
  const n = toAmount(value);
  return n === null ? '—' : FIXED2.format(n);
}

export function percent(ratio: Ratio | null | undefined): string {
  const v = toAmount(ratio?.value);
  return v === null ? '—' : PERCENT.format(v);
}

export function unavailable(ratio: Ratio | null | undefined): string | null {
  if (!ratio || ratio.value !== null) {
    return null;
  }
  return UNAVAILABLE_LABELS[ratio.unavailableReason ?? ''] ?? 'indisponível';
}

/**
 * Texto do denominador: "1.100 de 4.000" para contagens, "R$ 1.100,00 de R$ 4.000,00" para valores.
 * Indisponível → o motivo.
 */
export function ratioDetail(ratio: Ratio | null | undefined, kind: 'count' | 'money' = 'count'): string {
  if (!ratio) {
    return '';
  }
  const reason = unavailable(ratio);
  if (reason && (ratio.denominator === null || num(ratio.denominator) === 0)) {
    return reason;
  }
  const f = (v: Money | null) => (kind === 'money' ? formatAmount(v) : count(toAmount(v)));
  return `${f(ratio.numerator)} de ${f(ratio.denominator)}`;
}

export function seconds(value: Money | null | undefined): string {
  const n = toAmount(value);
  if (n === null) {
    return '—';
  }
  const total = Math.round(n);
  const m = Math.floor(total / 60);
  const s = total % 60;
  return m > 0 ? `${m} min ${String(s).padStart(2, '0')} s` : `${s} s`;
}

export function days(value: Money | null | undefined): string {
  const n = toAmount(value);
  return n === null ? '—' : `${DECIMAL.format(n)} dia${n === 1 ? '' : 's'}`;
}
