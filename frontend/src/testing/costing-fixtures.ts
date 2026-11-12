import { CreditorMargin, MarginLine, MarginReport, MarginTrend, TrendPoint } from '../app/features/costing/data/costing.models';

/** Linha de margem fictícia (valores redondos para conferir a formatação). */
export function marginLine(o: Partial<MarginLine> = {}): MarginLine {
  return {
    recovered: 10000,
    revenue: 2000,
    costs: { HUMAN_DIRECT: 600, AI: 50, TELEPHONY: 80, MESSAGING: 40, VARIABLE_COMMISSION: 200, LEGAL: 30, HUMAN_IDLE: 100, OVERHEAD: 150 },
    directCost: 1000,
    totalCost: 1250,
    contributionMargin: 1000,
    netMargin: 750,
    contributionMarginRate: 0.5,
    netMarginRate: 0.375,
    roi: 0.6,
    costPerRecovered: 0.125,
    settledAgreements: 5,
    costPerSettledAgreement: 250,
    directHours: 40,
    idleHours: 8,
    ...o,
  };
}

export const CREDITOR_A = '00000000-0000-0000-0000-0000000000c1';
export const CREDITOR_B = '00000000-0000-0000-0000-0000000000c2';
export const PORTFOLIO_A1 = '00000000-0000-0000-0000-0000000000a1';

/** Resposta de `GET /costing/margin` com um credor que rende e outro com margem negativa. */
export function marginReport(o: Partial<MarginReport> = {}): MarginReport {
  const rends: CreditorMargin = {
    ...marginLine({ revenue: 1500, netMargin: 900, netMarginRate: 0.6 }),
    creditorId: CREDITOR_A,
    portfolios: [{ ...marginLine({ revenue: 1500, netMargin: 900 }), portfolioId: PORTFOLIO_A1 }],
  };
  const consumes: CreditorMargin = {
    ...marginLine({ revenue: 500, contributionMargin: -50, netMargin: -150, netMarginRate: -0.3 }),
    creditorId: CREDITOR_B,
    portfolios: [],
  };
  return {
    period: { from: '2026-10-01', to: '2026-10-31' },
    basis: 'ESTIMATED',
    totals: marginLine(),
    creditors: [rends, consumes],
    months: [{ ...marginLine(), month: '2026-10' }],
    warnings: [],
    ...o,
  };
}

export const TREND_MONTHS = ['2026-05', '2026-06', '2026-07', '2026-08', '2026-09', '2026-10'];

/** Ponto da tendência: só a taxa (fração) e a margem; `null` = mês sem receita ou sem fato. */
export function trendPoint(month: string, rate: number | null, netMargin: number | null = rate === null ? null : rate * 1000): TrendPoint {
  return { month, recovered: netMargin === null ? null : 5000, revenue: rate === null ? null : 1000, netMargin, netMarginRate: rate };
}

/** Resposta de `GET /costing/margin/trend`: A subindo de 8% para 12% com maio sem receita; B caindo até ficar negativa. */
export function marginTrend(o: Partial<MarginTrend> = {}): MarginTrend {
  const rates = (values: (number | null)[]) => values.map((v, i) => trendPoint(TREND_MONTHS[i], v));
  return {
    period: { from: '2026-10-01', to: '2026-10-31' },
    months: TREND_MONTHS.map((month) => ({ month, status: 'COMPLETE' })),
    computedUntil: '2026-11-11',
    creditors: [
      { creditorId: CREDITOR_A, points: rates([null, 0.08, 0.09, 0.1, 0.11, 0.12]), portfolios: [{ portfolioId: PORTFOLIO_A1, points: rates([null, 0.08, 0.09, 0.1, 0.11, 0.12]) }] },
      { creditorId: CREDITOR_B, points: rates([0.1, 0.05, 0.02, -0.1, -0.2, -0.3]), portfolios: [] },
    ],
    ...o,
  };
}
