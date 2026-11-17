import { NO_GROUP_KEY, focusable, focusableKey, focusFilter, unfocusFilter } from './url-filter';
import { AnalyticsFilter } from './analytics.models';

const base: AnalyticsFilter = {
  from: '2026-10-01', to: '2026-10-31', creditorIds: ['a', 'b'], portfolioIds: [], operatorIds: [], teamIds: [], channels: [], groupBy: 'CREDITOR',
};

describe('filtro pelo clique no gráfico (ux.md §22)', () => {
  it('só dimensões da barra de filtros filtram; o item substitui a seleção da dimensão', () => {
    expect(focusable('CREDITOR')).toBe(true);
    expect(focusable('TEAM')).toBe(true);
    expect(focusable('MONTH')).toBe(false);
    expect(focusable('ORIGIN')).toBe(false);
    expect(focusable(null)).toBe(false);
    expect(focusFilter(base, 'CREDITOR', 'x')!.creditorIds).toEqual(['x']);
    expect(focusFilter(base, 'OPERATOR', 'u')!.operatorIds).toEqual(['u']);
    expect(focusFilter(base, 'MONTH', '2026-10')).toBeNull();
    // "Sem carteira"/"Sem negociador" e "Outros" não têm filtro equivalente (a barra não vira botão)
    expect(focusableKey(NO_GROUP_KEY)).toBe(false);
    expect(focusableKey('__outros__')).toBe(false);
    expect(focusFilter(base, 'PORTFOLIO', NO_GROUP_KEY)).toBeNull();
  });

  it('remover limpa só a dimensão', () => {
    const f = unfocusFilter({ ...base, channels: ['WHATSAPP'] }, 'CREDITOR');
    expect(f.creditorIds).toEqual([]);
    expect(f.channels).toEqual(['WHATSAPP']);
    expect(unfocusFilter(base, 'MONTH')).toBe(base);
  });
});
