import { CostBand } from './costing.models';
import { monthLabel } from './margin.rules';

/** Regras puras da configuração de custos (vigências, meses e faixas). */

export interface Validity {
  validFrom: string;
  validTo: string | null;
}

/** Em vigor em `today` (`AAAA-MM-DD`): começou e não terminou (o fim é inclusivo). */
export function isCurrent(item: Validity, today: string): boolean {
  return item.validFrom <= today && (item.validTo === null || item.validTo >= today);
}

/** Ainda pode ser encerrada: sem fim ou com fim no futuro. */
export function isOpen(item: Validity, today: string): boolean {
  return item.validTo === null || item.validTo >= today;
}

/** "a partir de 01/10/2026", "de 01/01/2026 a 30/09/2026". */
export function validityText(item: Validity): string {
  const br = (iso: string) => {
    const [y, m, d] = iso.split('-');
    return `${d}/${m}/${y}`;
  };
  return item.validTo ? `de ${br(item.validFrom)} a ${br(item.validTo)}` : `a partir de ${br(item.validFrom)}`;
}

/** Vigências agrupadas pelo nome (A→Z) e, dentro do nome, da mais recente para a mais antiga. */
export function sortBands(bands: readonly CostBand[]): CostBand[] {
  return [...bands].sort((a, b) => a.name.localeCompare(b.name, 'pt-BR') || b.validFrom.localeCompare(a.validFrom));
}

/** Nomes de faixa que podem receber pessoas (alguma vigência aberta), sem repetição, A→Z. */
export function assignableBandNames(bands: readonly CostBand[], today: string): string[] {
  return [...new Set(bands.filter((b) => isOpen(b, today)).map((b) => b.name))].sort((a, b) => a.localeCompare(b, 'pt-BR'));
}

/** Primeiro dia do mês (`AAAA-MM-01`). */
export function monthStart(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-01`;
}

/** Meses para escolha (do mais recente ao mais antigo): `forward` meses à frente e `back` meses para trás. */
export function monthOptions(today: Date, back = 24, forward = 1): { value: string; label: string }[] {
  const out: { value: string; label: string }[] = [];
  for (let i = forward; i >= -back; i--) {
    const value = monthStart(new Date(today.getFullYear(), today.getMonth() + i, 1));
    out.push({ value, label: monthLabel(value) });
  }
  return out;
}

/** Mês fechado mais recente (padrão do câmbio e dos custos fixos). */
export function lastClosedMonthStart(today: Date): string {
  return monthStart(new Date(today.getFullYear(), today.getMonth() - 1, 1));
}

/** Normaliza o mês do servidor (`AAAA-MM` ou `AAAA-MM-DD`) para o primeiro dia. */
export function normalizeMonth(month: string): string {
  const match = /^(\d{4})-(\d{2})/.exec(month);
  return match ? `${match[1]}-${match[2]}-01` : month;
}
