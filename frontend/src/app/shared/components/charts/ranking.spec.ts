import { ChartRow, ChartSeries } from './chart.models';
import { OTHERS_KEY, parseRankSort, rankTable, rankView, seriesTotals } from './ranking';

const series: ChartSeries[] = [
  { name: 'Carteira', color: '--app-chart-1', format: (v) => `R$ ${v}` },
  { name: 'Vencido', color: '--app-chart-2', format: (v) => `R$ ${v}` },
];
const noun = { one: 'credor', many: 'credores' };
const rows = (n: number, value = (i: number) => (n - i) * 10): ChartRow[] =>
  Array.from({ length: n }, (_, i) => ({ key: `c${i}`, label: `Credor ${i}`, values: [value(i), value(i) / 2], display: ['', ''] }));

describe('ranking dos gráficos (ux.md §22)', () => {
  it('até N+1 itens mostra todos, sem "Outros" nem aviso', () => {
    for (const n of [0, 1, 10, 11]) {
      const v = rankView(rows(n), series, 10, noun);
      expect(v.bars).toHaveLength(n);
      expect(v.others).toBeNull();
      expect(v.notice).toBeNull();
    }
  });

  it('com N+2 ou mais: os N primeiros e "Outros" por último, somando o resto, e as barras fecham com o total', () => {
    const all = rows(47);
    const v = rankView(all, series, 10, noun, { shareOf: 'da carteira' });
    expect(v.bars).toHaveLength(11);
    expect(v.bars.at(-1)!.key).toBe(OTHERS_KEY);
    expect(v.others!.label).toBe('Outros (37 credores)');
    const sum = (rs: ChartRow[], i: number) => rs.reduce((s, r) => s + r.values[i], 0);
    expect(sum(v.bars, 0)).toBe(sum(all, 0));
    expect(sum(v.bars, 1)).toBe(sum(all, 1));
    expect(v.others!.display[0]).toBe(`R$ ${sum(all.slice(10), 0)}`);
    const share = Math.round((sum(all.slice(0, 10), 0) / sum(all, 0)) * 100);
    expect(v.notice).toBe(`Mostrando 10 de 47 credores, que somam ${share}% da carteira.`);
  });

  it('resto zerado não vira "Outros"; sem critério o aviso é curto; valor negativo não dá participação', () => {
    // sinais mistos no resto (+5/−5): "Outros" aparece (com 0), os itens não somem sem aviso
    const mixed = rows(13, (i) => (i < 10 ? 100 - i : i === 10 ? 5 : i === 11 ? -5 : 0));
    expect(rankView(mixed, series, 10, noun).others!.values[0]).toBe(0);
    const zeros = rows(13, (i) => (i < 10 ? 100 - i : 0));
    expect(rankView(zeros, series, 10, noun).others).toBeNull();
    expect(rankView(zeros, series, 10, noun).notice).toBe('Mostrando 10 de 13 credores.');
    const negative = rows(13, (i) => (i === 12 ? -5 : 100 - i));
    expect(rankView(negative, series, 10, noun, { shareOf: 'do total' }).notice).toBe('Mostrando 10 de 13 credores.');
  });

  it('valor negativo entra nas somas: "Outros" e o total batem com as linhas', () => {
    const neg = rows(14, (i) => (i === 13 ? -50 : 100 - i));
    const v = rankView(neg, series, 10, noun);
    expect(v.others!.values[0]).toBe(neg.slice(10).reduce((s, r) => s + r.values[0], 0));
    expect(seriesTotals(neg, 1)[0]).toBe(neg.reduce((s, r) => s + r.values[0], 0));
  });

  it('sem limite, sem ranking', () => {
    expect(rankView(rows(40), series, null, noun).bars).toHaveLength(40);
  });

  it('busca sem acento nem maiúscula, ordenação estável e totais', () => {
    expect(rankTable([{ key: 'x', label: 'Banco  Sul', values: [1], display: [] }], 'banco sul', null)).toHaveLength(1);
    const list: ChartRow[] = [
      { key: 'a', label: 'Banco Ômega', values: [5, 1], display: [] },
      { key: 'b', label: 'Varejo Sul', values: [9, 1], display: [] },
      { key: 'c', label: 'banco alfa', values: [5, 3], display: [] },
    ];
    expect(rankTable(list, 'omega', null).map((r) => r.key)).toEqual(['a']);
    expect(rankTable(list, 'BANCO', null).map((r) => r.key)).toEqual(['a', 'c']);
    expect(rankTable(list, '', { column: 0, desc: true }).map((r) => r.key)).toEqual(['b', 'a', 'c']);
    expect(rankTable(list, '', { column: -1, desc: false }).map((r) => r.key)).toEqual(['c', 'a', 'b']);
    expect(seriesTotals(list, 2)).toEqual([19, 5]);
  });

  it('ordem na URL', () => {
    expect(parseRankSort('-1', 2)).toEqual({ column: 1, desc: true });
    expect(parseRankSort('nome', 2)).toEqual({ column: -1, desc: false });
    expect(parseRankSort('5', 2)).toBeNull();
    expect(parseRankSort(null, 2)).toBeNull();
  });
});
