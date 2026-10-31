import {
  EMPTY_SELECTION,
  decisionSummary,
  decisionTarget,
  headerState,
  isSelected,
  selectAllInFilter,
  selectedCount,
  selectionBanner,
  toggleOne,
  togglePage,
} from './pdf-record-selection';

const PAGE = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];
const FILTER = { status: 'LOW_CONFIDENCE' as const, debtor: null, q: null };

describe('seleção de títulos da Importação PDF', () => {
  it('checkbox do cabeçalho: nenhum, alguns (indeterminado) e todos da página', () => {
    expect(headerState(EMPTY_SELECTION, PAGE)).toBe('none');
    const one = toggleOne(EMPTY_SELECTION, 3, PAGE);
    expect(headerState(one, PAGE)).toBe('some');
    const page = togglePage(one, PAGE);
    expect(headerState(page, PAGE)).toBe('all');
    expect(headerState(togglePage(page, PAGE), PAGE)).toBe('none');
  });

  it('a seleção sobrevive à troca de página', () => {
    const first = togglePage(EMPTY_SELECTION, PAGE);
    const second = toggleOne(first, 51, [51, 52]);
    expect(selectedCount(second, 57)).toBe(11);
    expect(headerState(second, PAGE)).toBe('all');
    expect(headerState(second, [51, 52])).toBe('some');
  });

  it('aviso do Gmail: 10 nesta página → selecionar todos os 57 do filtro; a ação vai pelo critério do filtro', () => {
    const page = togglePage(EMPTY_SELECTION, PAGE);
    expect(selectionBanner(page, PAGE, 57)).toEqual({ text: '10 selecionados nesta página.', action: 'select-all' });
    expect(decisionTarget(page, FILTER, 57)).toEqual({ ordinals: PAGE });
    const all = selectAllInFilter();
    expect(selectedCount(all, 57)).toBe(57);
    expect(isSelected(all, 42)).toBe(true);
    expect(selectionBanner(all, PAGE, 57)).toEqual({ text: 'Todos os 57 títulos do filtro estão selecionados.', action: 'clear' });
    expect(decisionTarget(all, FILTER, 57)).toEqual({ filter: FILTER, expectedCount: 57 });
    // sem mais títulos que a página, não há o que oferecer
    expect(selectionBanner(page, PAGE, 10)).toBeNull();
  });

  it('desmarcar uma linha com todos do filtro selecionados volta à seleção da página', () => {
    const next = toggleOne(selectAllInFilter(), 4, PAGE);
    expect(next.allInFilter).toBe(false);
    expect(selectedCount(next, 57)).toBe(9);
    expect(headerState(next, PAGE)).toBe('some');
  });

  it('resume o que foi aplicado e o que ficou de fora', () => {
    expect(decisionSummary(3, 'confirmado(s)', [{ code: 'low_confidence_one_by_one', message: 'têm confiança baixa: confirme um a um', ordinals: [2, 4] }])).toBe(
      '3 título(s) confirmado(s). Ficaram de fora: 2 têm confiança baixa: confirme um a um (nº 2, nº 4).',
    );
    expect(decisionSummary(0, 'excluído(s) do lote', [])).toBe('Nenhum título excluído(s) do lote.');
  });
});
