import { formatAmount, toAmount } from '../../../shared/components/amount/amount';
import { CreditorMargin } from './costing.models';

/**
 * Regras puras do mapa de dispersão carteira × margem líquida % × volume recuperado (margem-por-carteira.md §14.2):
 * quem entra no mapa, os dois cortes dos quadrantes, o quadrante de cada carteira e a escala.
 *
 * Cortes (explicáveis, recalculados com o que está no mapa):
 * - **volume**: a mediana do recuperado das carteiras do mapa (metade fica de cada lado; não depende de um valor
 *   fixo que não serve para assessorias de tamanhos diferentes);
 * - **margem**: a margem líquida % do conjunto do mapa (soma das margens ÷ soma das receitas), ou zero quando ela é
 *   negativa (margem abaixo de zero nunca "rende").
 * Carteira com recuperado ≥ corte de volume tem volume alto; com margem ≥ corte **e** positiva tem margem alta.
 */

export type Quadrant = 'scale' | 'niche' | 'consumes' | 'review';

/** Rótulos do desenho (§6.1), com a leitura de cada quadrante. */
export const QUADRANTS: Readonly<Record<Quadrant, { label: string; reading: string }>> = {
  scale: { label: 'Rende e escala', reading: 'margem e volume altos' },
  niche: { label: 'Rende pouco', reading: 'margem alta, volume baixo' },
  consumes: { label: 'Consome', reading: 'volume alto, margem baixa' },
  review: { label: 'Revisar contrato', reading: 'volume e margem baixos' },
};

export interface ScatterPoint {
  key: string;
  creditorId: string;
  portfolioId: string;
  creditorName: string;
  portfolioName: string;
  recovered: number;
  revenue: number;
  netMargin: number;
  /** Margem líquida em fração da receita. */
  rate: number;
  negative: boolean;
  quadrant: Quadrant;
}

export interface ScatterModel {
  points: ScatterPoint[];
  /** Carteiras do período fora do mapa: sem receita (margem % sem base) ou sem valor recuperado. */
  withoutBasis: number;
  /** Corte de volume (R$): mediana do recuperado. */
  volumeCut: number;
  /** Corte de margem (fração). */
  marginCut: number;
  /** De onde veio o corte de margem: margem do conjunto ou zero (conjunto negativo). */
  marginCutSource: 'aggregate' | 'zero';
}

export function median(values: readonly number[]): number {
  if (!values.length) {
    return 0;
  }
  const sorted = [...values].sort((a, b) => a - b);
  const mid = Math.floor(sorted.length / 2);
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2;
}

export function quadrantOf(recovered: number, rate: number, volumeCut: number, marginCut: number): Quadrant {
  const highVolume = recovered >= volumeCut;
  const highMargin = rate > 0 && rate >= marginCut;
  if (highMargin) {
    return highVolume ? 'scale' : 'niche';
  }
  return highVolume ? 'consumes' : 'review';
}

/**
 * Monta o mapa a partir das carteiras da resposta de `/margin`. A linha "sem carteira" (custos do credor sem
 * carteira) não é carteira e não entra nem na contagem; carteira sem receita ou sem recuperado fica fora do mapa e
 * entra em `withoutBasis`.
 */
export function scatterModel(
  creditors: readonly CreditorMargin[],
  creditorName: (c: CreditorMargin) => string,
  portfolioName: (creditor: CreditorMargin, portfolioId: string) => string,
): ScatterModel {
  const rows: Omit<ScatterPoint, 'quadrant'>[] = [];
  let withoutBasis = 0;
  for (const c of creditors) {
    for (const p of c.portfolios ?? []) {
      if (!p.portfolioId) {
        continue;
      }
      const recovered = toAmount(p.recovered) ?? 0;
      const revenue = toAmount(p.revenue) ?? 0;
      const rate = toAmount(p.netMarginRate);
      if (rate === null || revenue <= 0 || recovered <= 0) {
        withoutBasis++;
        continue;
      }
      const netMargin = toAmount(p.netMargin) ?? 0;
      rows.push({
        key: `${c.creditorId}|${p.portfolioId}`,
        creditorId: c.creditorId,
        portfolioId: p.portfolioId,
        creditorName: creditorName(c),
        portfolioName: portfolioName(c, p.portfolioId),
        recovered,
        revenue,
        netMargin,
        rate,
        negative: netMargin < 0,
      });
    }
  }
  const volumeCut = median(rows.map((r) => r.recovered));
  const revenue = rows.reduce((sum, r) => sum + r.revenue, 0);
  const aggregate = revenue > 0 ? rows.reduce((sum, r) => sum + r.netMargin, 0) / revenue : 0;
  const marginCut = Math.max(0, aggregate);
  return {
    points: rows.map((r) => ({ ...r, quadrant: quadrantOf(r.recovered, r.rate, volumeCut, marginCut) })),
    withoutBasis,
    volumeCut,
    marginCut,
    marginCutSource: aggregate > 0 ? 'aggregate' : 'zero',
  };
}

const PERCENT = new Intl.NumberFormat('pt-BR', { style: 'percent', minimumFractionDigits: 1, maximumFractionDigits: 1 });

export function percentText(value: number): string {
  return PERCENT.format(value).replace(/ /g, ' ');
}

/** Texto dos cortes, igual na tela e no "i": "Volume: mediana de R$ 50.000,00. Margem: 12,0% (margem do conjunto)." */
export function cutsText(model: ScatterModel): string {
  const margin =
    model.marginCutSource === 'aggregate'
      ? `${percentText(model.marginCut)} da receita (margem líquida do conjunto do mapa)`
      : '0% (a margem do conjunto do mapa é negativa ou zero)';
  return `Cortes: volume na mediana do recuperado, ${formatAmount(model.volumeCut)}; margem em ${margin}.`;
}

/** Rótulo acessível do ponto (cor nunca é o único sinal: a margem negativa vem escrita). */
export function pointLabel(p: ScatterPoint): string {
  const margin = `${percentText(p.rate)}${p.negative ? ' (negativa)' : ''}`;
  return `${p.portfolioName}, ${p.creditorName}: recuperado ${formatAmount(p.recovered)}, receita ${formatAmount(p.revenue)}, margem líquida ${margin} da receita. Quadrante: ${QUADRANTS[p.quadrant].label}.`;
}

/** Resumo do mapa (texto alternativo): quantas carteiras por quadrante e quantas fora. */
export function scatterSummary(model: ScatterModel): string {
  if (!model.points.length) {
    return model.withoutBasis
      ? `Nenhuma carteira com receita e recuperado no período; ${model.withoutBasis} sem base.`
      : 'Nenhuma carteira no período.';
  }
  const count = (q: Quadrant) => model.points.filter((p) => p.quadrant === q).length;
  const parts = (Object.keys(QUADRANTS) as Quadrant[]).map((q) => `${QUADRANTS[q].label}: ${count(q)}`);
  const negatives = model.points.filter((p) => p.negative).length;
  return `${model.points.length} ${model.points.length === 1 ? 'carteira' : 'carteiras'} no mapa. ${parts.join('; ')}.${
    negatives ? ` ${negatives} com margem negativa.` : ''
  }`;
}

// ------------------------------------------------------------------ escala

/** Menor margem desenhada: abaixo de −100% o ponto fica na borda, marcado (a dica traz o valor real). */
export const MIN_RATE_SHOWN = -1;

/**
 * Escala logarítmica do recuperado (volumes de carteiras costumam variar de milhares a milhões: na escala linear as
 * pequenas se amontoam no canto). Domínio em potências de 10 que cobrem os pontos e o corte; marcas em cada potência.
 */
export function logDomain(values: readonly number[]): { lo: number; hi: number; ticks: number[] } {
  const positive = values.filter((v) => v > 0);
  if (!positive.length) {
    return { lo: 1, hi: 10, ticks: [1, 10] };
  }
  let lo = Math.floor(Math.log10(Math.min(...positive)));
  let hi = Math.ceil(Math.log10(Math.max(...positive)));
  if (hi <= lo) {
    hi = lo + 1;
  }
  const ticks: number[] = [];
  for (let e = lo; e <= hi; e++) {
    ticks.push(Math.pow(10, e));
  }
  return { lo: Math.pow(10, lo), hi: Math.pow(10, hi), ticks };
}

/** Domínio vertical (fração): cobre os pontos (até −100%), o zero e o corte, com folga de 5 pontos. */
export function rateDomain(rates: readonly number[], cut: number): { lo: number; hi: number } {
  const shown = rates.map((r) => Math.max(MIN_RATE_SHOWN, r));
  const lo = Math.min(0, cut, ...shown) - 0.05;
  const hi = Math.max(0, cut, ...shown) + 0.05;
  return { lo, hi: Math.min(1.05, hi) };
}
