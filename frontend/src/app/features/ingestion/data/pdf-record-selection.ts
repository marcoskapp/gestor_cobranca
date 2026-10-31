import { DecisionTarget, RecordFilter, RecordStatusFilter, SkippedRecords } from './pdf-import.models';

/**
 * Seleção de títulos da tela de validação da Importação PDF, no padrão do Gmail: títulos marcados um a um (a marcação
 * sobrevive à troca de página) ou "todos os N do filtro" (a ação vai pelo critério do filtro, com a quantidade vista,
 * e o servidor recusa se o filtro mudou). Imutável: cada operação devolve uma seleção nova.
 */
export interface RecordSelection {
  /** Títulos marcados (fora do modo "todos do filtro"). */
  readonly ordinals: ReadonlySet<number>;
  /** Todos os títulos do filtro corrente. */
  readonly allInFilter: boolean;
}

export type HeaderState = 'none' | 'some' | 'all';

export const EMPTY_SELECTION: RecordSelection = { ordinals: new Set(), allInFilter: false };

export const STATUS_FILTERS: readonly { value: RecordStatusFilter; label: string }[] = [
  { value: 'ALL', label: 'Todos' },
  { value: 'OK', label: 'OK' },
  { value: 'WARNING', label: 'Com aviso' },
  { value: 'ERROR', label: 'Com erro' },
  { value: 'LOW_CONFIDENCE', label: 'Confiança baixa (leitura visual)' },
  { value: 'EXCLUDED', label: 'Excluídos' },
];

export const DEFAULT_FILTER: RecordFilter = { status: 'ALL', debtor: null, q: null };

/** Quantos títulos a ação alcança. */
export function selectedCount(selection: RecordSelection, filterTotal: number): number {
  return selection.allInFilter ? filterTotal : selection.ordinals.size;
}

export function isSelected(selection: RecordSelection, ordinal: number): boolean {
  return selection.allInFilter || selection.ordinals.has(ordinal);
}

/** Estado do checkbox do cabeçalho em relação à página visível (indeterminado = alguns). */
export function headerState(selection: RecordSelection, page: readonly number[]): HeaderState {
  if (!page.length) {
    return 'none';
  }
  const marked = page.filter((o) => isSelected(selection, o)).length;
  return marked === 0 ? 'none' : marked === page.length ? 'all' : 'some';
}

/** Marca ou desmarca uma linha; desmarcar no modo "todos do filtro" volta à seleção da página sem essa linha. */
export function toggleOne(selection: RecordSelection, ordinal: number, page: readonly number[]): RecordSelection {
  if (selection.allInFilter) {
    return { ordinals: new Set(page.filter((o) => o !== ordinal)), allInFilter: false };
  }
  const next = new Set(selection.ordinals);
  if (next.has(ordinal)) {
    next.delete(ordinal);
  } else {
    next.add(ordinal);
  }
  return { ordinals: next, allInFilter: false };
}

/** Checkbox do cabeçalho: com a página inteira marcada desmarca a página; senão marca a página toda. */
export function togglePage(selection: RecordSelection, page: readonly number[]): RecordSelection {
  if (headerState(selection, page) === 'all') {
    if (selection.allInFilter) {
      return EMPTY_SELECTION;
    }
    const next = new Set(selection.ordinals);
    page.forEach((o) => next.delete(o));
    return { ordinals: next, allInFilter: false };
  }
  const base = selection.allInFilter ? new Set<number>() : new Set(selection.ordinals);
  page.forEach((o) => base.add(o));
  return { ordinals: base, allInFilter: false };
}

export function selectAllInFilter(): RecordSelection {
  return { ordinals: new Set(), allInFilter: true };
}

/**
 * Aviso no padrão do Gmail: com a página inteira marcada e mais títulos no filtro, oferece "Selecionar todos os N do
 * filtro"; com todos do filtro marcados, informa e oferece limpar.
 */
export function selectionBanner(
  selection: RecordSelection,
  page: readonly number[],
  filterTotal: number,
): { text: string; action: 'select-all' | 'clear' } | null {
  if (selection.allInFilter) {
    return { text: `Todos os ${filterTotal} títulos do filtro estão selecionados.`, action: 'clear' };
  }
  if (headerState(selection, page) === 'all' && filterTotal > page.length) {
    return { text: `${page.length} selecionados nesta página.`, action: 'select-all' };
  }
  return null;
}

/** Alvo da decisão enviado ao servidor. */
export function decisionTarget(selection: RecordSelection, filter: RecordFilter, filterTotal: number): DecisionTarget {
  return selection.allInFilter ? { filter, expectedCount: filterTotal } : { ordinals: [...selection.ordinals].sort((a, b) => a - b) };
}

export function sameFilter(a: RecordFilter, b: RecordFilter): boolean {
  return a.status === b.status && a.debtor === b.debtor && (a.q ?? '').trim() === (b.q ?? '').trim();
}

/** Resumo do resultado da ação em massa: aplicados e o que ficou de fora, por motivo. */
export function decisionSummary(applied: number, verb: string, skipped: readonly SkippedRecords[]): string {
  const head = applied ? `${applied} título(s) ${verb}.` : `Nenhum título ${verb}.`;
  const rest = skipped.map((g) => `${g.ordinals.length} ${g.message} (${listOrdinals(g.ordinals)})`);
  return rest.length ? `${head} Ficaram de fora: ${rest.join('; ')}.` : head;
}

function listOrdinals(ordinals: readonly number[]): string {
  const shown = ordinals.slice(0, 8).map((o) => `nº ${o}`);
  return ordinals.length > 8 ? `${shown.join(', ')} e mais ${ordinals.length - 8}` : shown.join(', ');
}
