import { mergeCumulative } from './cumulative-chart';

describe('acumulado diário (posição do credor)', () => {
  it('alinha o período atual e o anterior pelo dia do período', () => {
    const rows = mergeCumulative(
      [{ day: 1, v: 10 }, { day: 2, v: 25 }],
      [{ day: 1, v: 5 }, { day: 2, v: 8 }, { day: 3, v: 30 }],
      (p) => p.v,
    );
    expect(rows).toEqual([
      { day: 1, current: 10, previous: 5 },
      { day: 2, current: 25, previous: 8 },
      { day: 3, current: null, previous: 30 },
    ]);
  });

  it('sem dados, sem linhas', () => {
    expect(mergeCumulative<{ day: number }>([], [], () => 0)).toEqual([]);
  });
});
