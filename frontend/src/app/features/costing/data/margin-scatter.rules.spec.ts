import { marginLine } from '../../../../testing/costing-fixtures';
import { CreditorMargin } from './costing.models';
import { cutsText, logDomain, median, pointLabel, quadrantOf, rateDomain, scatterModel, scatterSummary } from './margin-scatter.rules';

const nbsp = (text: string) => text.replace(/ /g, ' ');

function creditor(id: string, portfolios: { id: string | null; recovered: number; revenue: number; netMargin: number }[]): CreditorMargin {
  return {
    ...marginLine(),
    creditorId: id,
    portfolios: portfolios.map((p) => ({
      ...marginLine({ recovered: p.recovered, revenue: p.revenue, netMargin: p.netMargin, netMarginRate: p.revenue > 0 ? p.netMargin / p.revenue : null }),
      portfolioId: p.id,
    })),
  };
}

const creditorName = (c: CreditorMargin) => `Credor ${c.creditorId}`;
const portfolioName = (_: CreditorMargin, id: string) => `Carteira ${id}`;

describe('margem por carteira — mapa de dispersão', () => {
  it('mediana com quantidade par e ímpar', () => {
    expect(median([5, 1, 3])).toBe(3);
    expect(median([4, 1, 3, 2])).toBe(2.5);
    expect(median([])).toBe(0);
  });

  it('quadrantes: volume ≥ corte é alto; margem ≥ corte e positiva é alta', () => {
    expect(quadrantOf(100, 0.3, 50, 0.1)).toBe('scale');
    expect(quadrantOf(10, 0.3, 50, 0.1)).toBe('niche');
    expect(quadrantOf(100, 0.05, 50, 0.1)).toBe('consumes');
    expect(quadrantOf(10, 0.05, 50, 0.1)).toBe('review');
    // no corte conta como alto
    expect(quadrantOf(50, 0.1, 50, 0.1)).toBe('scale');
    // corte zero (conjunto negativo): margem zero não "rende"
    expect(quadrantOf(100, 0, 50, 0)).toBe('consumes');
  });

  it('cortes pela mediana do recuperado e pela margem do conjunto; sem base fora, "sem carteira" ignorada', () => {
    const model = scatterModel(
      [
        creditor('a', [
          { id: 'p1', recovered: 100_000, revenue: 10_000, netMargin: 3_000 }, // 30%
          { id: 'p2', recovered: 5_000, revenue: 1_000, netMargin: 400 }, // 40%
          { id: 'p3', recovered: 0, revenue: 0, netMargin: -200 }, // sem receita: fora
          { id: null, recovered: 0, revenue: 0, netMargin: -500 }, // custos do credor sem carteira: não é carteira
        ]),
        creditor('b', [
          { id: 'p4', recovered: 2_000_000, revenue: 20_000, netMargin: -2_000 }, // −10%
          { id: 'p5', recovered: 1_000, revenue: 500, netMargin: -100 }, // −20%
        ]),
      ],
      creditorName,
      portfolioName,
    );
    expect(model.withoutBasis).toBe(1);
    expect(model.points.length).toBe(4);
    // mediana de 1.000, 5.000, 100.000 e 2.000.000
    expect(model.volumeCut).toBe(52_500);
    // (3.000 + 400 − 2.000 − 100) ÷ 31.500
    expect(model.marginCut).toBeCloseTo(1300 / 31500, 6);
    expect(model.marginCutSource).toBe('aggregate');
    const q = Object.fromEntries(model.points.map((p) => [p.portfolioId, p.quadrant]));
    expect(q).toEqual({ p1: 'scale', p2: 'niche', p4: 'consumes', p5: 'review' });
    expect(model.points.find((p) => p.portfolioId === 'p4')!.negative).toBe(true);
    expect(nbsp(cutsText(model))).toBe('Cortes: volume na mediana do recuperado, R$ 52.500,00; margem em 4,1% da receita (margem líquida do conjunto do mapa).');
    expect(scatterSummary(model)).toBe('4 carteiras no mapa. Rende e escala: 1; Rende pouco: 1; Consome: 1; Revisar contrato: 1. 2 com margem negativa.');
    expect(nbsp(pointLabel(model.points.find((p) => p.portfolioId === 'p4')!))).toBe(
      'Carteira p4, Credor b: recuperado R$ 2.000.000,00, receita R$ 20.000,00, margem líquida -10,0% (negativa) da receita. Quadrante: Consome.',
    );
  });

  it('conjunto com margem negativa: corte de margem em zero, explicado', () => {
    const model = scatterModel([creditor('a', [{ id: 'p1', recovered: 1_000, revenue: 100, netMargin: -50 }])], creditorName, portfolioName);
    expect(model.marginCut).toBe(0);
    expect(model.marginCutSource).toBe('zero');
    expect(cutsText(model)).toContain('0% (a margem do conjunto do mapa é negativa ou zero)');
    expect(scatterModel([creditor('a', [{ id: 'p1', recovered: 0, revenue: 0, netMargin: -5 }])], creditorName, portfolioName).points).toEqual([]);
    expect(scatterSummary({ points: [], withoutBasis: 2, volumeCut: 0, marginCut: 0, marginCutSource: 'zero' })).toContain('2 sem base');
  });

  it('escala logarítmica em potências de 10 e domínio vertical com zero, corte e piso de −100%', () => {
    expect(logDomain([1_500, 2_300_000])).toEqual({ lo: 1_000, hi: 10_000_000, ticks: [1_000, 10_000, 100_000, 1_000_000, 10_000_000] });
    expect(logDomain([5_000, 5_000]).ticks).toEqual([1_000, 10_000]);
    const d = rateDomain([0.2, -3], 0.1);
    expect(d.lo).toBeCloseTo(-1.05);
    expect(d.hi).toBeCloseTo(0.25);
  });
});
