import { TREND_MONTHS, trendPoint } from '../../../../testing/costing-fixtures';
import { TrendMonth } from './costing.models';
import { shortMonth, sparkline, trendPointText, trendText } from './margin-trend.rules';

const months = (status: Partial<Record<string, string>> = {}): TrendMonth[] => TREND_MONTHS.map((month) => ({ month, status: status[month] ?? 'COMPLETE' }));
const series = (values: (number | null)[]) => values.map((v, i) => trendPoint(TREND_MONTHS[i], v));

describe('margem por carteira — tendência de 6 meses', () => {
  it('mês abreviado, com o ano quando a janela atravessa o ano', () => {
    expect(shortMonth('2026-06')).toBe('jun.');
    expect(shortMonth('2026-06', true)).toBe('jun. de 2026');
    const crossing: TrendMonth[] = ['2025-09', '2025-10', '2025-11', '2025-12', '2026-01', '2026-02'].map((month) => ({ month, status: 'COMPLETE' }));
    expect(trendText(crossing.map((m) => trendPoint(m.month, 0.1)), crossing)).toContain('de set. de 2025 a fev. de 2026');
  });

  it('frase compara o primeiro e o último mês com receita: subiu, caiu, estável', () => {
    expect(trendText(series([0.08, 0.09, 0.1, 0.1, 0.11, 0.12]), months())).toBe('Margem líquida de mai. a out.: subiu de 8,0% para 12,0% da receita.');
    expect(trendText(series([0.1, 0.05, 0.02, -0.1, -0.2, -0.3]), months())).toBe(
      'Margem líquida de mai. a out.: caiu de 10,0% para -30,0% (negativa) da receita.',
    );
    // menos de 1 ponto percentual de diferença: estável
    expect(trendText(series([0.1, 0.12, 0.08, 0.1, 0.1, 0.105]), months())).toBe('Margem líquida de mai. a out.: estável em 10,5% da receita.');
  });

  it('mês sem receita é lacuna e é contado; mês não apurado e parcial aparecem na frase', () => {
    expect(trendText(series([null, 0.08, null, 0.1, 0.11, 0.12]), months())).toBe(
      'Margem líquida de mai. a out.: subiu de 8,0% para 12,0% da receita. 2 meses sem receita.',
    );
    expect(trendText(series([0.08, 0.09, 0.1, 0.1, 0.11, null]), months({ '2026-10': 'NOT_REFRESHED', '2026-09': 'PARTIAL' }))).toBe(
      'Margem líquida de mai. a out.: subiu de 8,0% para 11,0% da receita. out. ainda não apurado; set. parcial.',
    );
    expect(trendText(series([null, null, null, null, null, 0.2]), months())).toBe('Margem líquida de mai. a out.: só out. tem receita, com 20,0% da receita.');
    expect(trendText(series([null, null, null, null, null, null]), months())).toBe('Margem líquida de mai. a out.: sem receita nos meses apurados.');
    expect(trendText([], [])).toBe('Tendência indisponível.');
  });

  it('linha do mês para a dica', () => {
    const [m] = months({ '2026-05': 'PARTIAL' });
    expect(trendPointText(trendPoint('2026-05', -0.05), m)).toBe('mai.: -5,0% (negativa) da receita (parcial)');
    expect(trendPointText(trendPoint('2026-05', null), { month: '2026-05', status: 'COMPLETE' })).toBe('mai.: sem receita');
    expect(trendPointText(trendPoint('2026-05', null), { month: '2026-05', status: 'NOT_REFRESHED' })).toBe('mai.: não apurado');
  });

  it('minigráfico: lacuna interrompe a linha, zero desenhado quando há negativo, último ponto marcado', () => {
    const g = sparkline(series([0.1, 0.05, null, -0.1, -0.2, -0.3]), 72, 22);
    expect(g.paths.length).toBe(2);
    expect(g.dots.length).toBe(5);
    expect(g.dots.filter((d) => d.negative).length).toBe(3);
    expect(g.dots.at(-1)!.last).toBe(true);
    expect(g.zeroY).not.toBeNull();
    // o maior valor fica em cima (y menor)
    expect(g.dots[0].y).toBeLessThan(g.dots[4].y);
    // só positivos: sem linha do zero; tudo vazio: nada a desenhar
    expect(sparkline(series([0.1, 0.2, 0.1, 0.2, 0.1, 0.2]), 72, 22).zeroY).toBeNull();
    expect(sparkline(series([null, null, null, null, null, null]), 72, 22)).toEqual({ paths: [], dots: [], zeroY: null });
    // ponto isolado não vira linha (só o marcador)
    expect(sparkline(series([0.1, null, 0.2, null, null, null]), 72, 22).paths).toEqual([]);
  });
});
