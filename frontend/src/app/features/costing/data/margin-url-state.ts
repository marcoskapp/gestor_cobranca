import { Signal, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { distinctUntilChanged, map } from 'rxjs';
import { MarginBasis, MarginFilter } from './costing.models';
import {
  MARGIN_BASIS_QUERY,
  MARGIN_QUERY,
  MarginSort,
  formatMarginBasis,
  formatMarginSort,
  marginFilterParams,
  parseMarginBasis,
  parseMarginFilter,
  parseMarginSort,
  sameMarginFilter,
} from './margin.rules';

export interface MarginUrlState {
  readonly filter: Signal<MarginFilter>;
  readonly sort: Signal<MarginSort>;
  update(filter: MarginFilter): void;
  /**
   * Abre outro recorte do painel como um novo passo do histórico (sem `replaceUrl`): o "voltar" do navegador retorna
   * ao recorte anterior (ex.: clique numa carteira do mapa de dispersão).
   */
  open(filter: MarginFilter): void;
  setSort(sort: MarginSort): void;
  /** Base dos custos de canal (`?base=conciliado`); padrão: estimada. */
  readonly basis: Signal<MarginBasis>;
  setBasis(basis: MarginBasis): void;
}

/**
 * Filtro e ordenação do painel de margem na URL (compartilhável, sobrevive ao recarregar e ao voltar — ux.md §16),
 * sempre com `replaceUrl`. Diferente dos painéis do analytics, o período padrão é o último mês fechado e o filtro não
 * é herdado de outro painel (a margem é mensal; os 30 dias dos outros painéis cortariam dois meses). Contexto de injeção.
 */
export function marginUrlState(): MarginUrlState {
  const route = inject(ActivatedRoute);
  const router = inject(Router);
  const filter = toSignal(
    route.queryParamMap.pipe(
      map((params) => parseMarginFilter(params, new Date())),
      distinctUntilChanged(sameMarginFilter),
    ),
    { initialValue: parseMarginFilter(route.snapshot.queryParamMap, new Date()) },
  );
  const sort = toSignal(route.queryParamMap.pipe(map((params) => parseMarginSort(params.get(MARGIN_QUERY.sort)))), {
    initialValue: parseMarginSort(route.snapshot.queryParamMap.get(MARGIN_QUERY.sort)),
  });
  const basis = toSignal(route.queryParamMap.pipe(map((params) => parseMarginBasis(params.get(MARGIN_BASIS_QUERY)))), {
    initialValue: parseMarginBasis(route.snapshot.queryParamMap.get(MARGIN_BASIS_QUERY)),
  });
  const navigate = (queryParams: Record<string, unknown>) =>
    void router.navigate([], { relativeTo: route, queryParams, queryParamsHandling: 'merge', replaceUrl: true, state: { urlState: true } });
  return {
    filter,
    sort,
    update(next: MarginFilter) {
      if (!sameMarginFilter(next, filter())) {
        navigate(marginFilterParams(next));
      }
    },
    open(next: MarginFilter) {
      if (!sameMarginFilter(next, filter())) {
        // passo novo do histórico (não é gravação de estado): a rolagem desta tela fica guardada para o "voltar"
        void router.navigate([], { relativeTo: route, queryParams: marginFilterParams(next), queryParamsHandling: 'merge' });
      }
    },
    setSort(next: MarginSort) {
      navigate({ [MARGIN_QUERY.sort]: formatMarginSort(next) });
    },
    basis,
    setBasis(next: MarginBasis) {
      if (next !== basis()) {
        navigate({ [MARGIN_BASIS_QUERY]: formatMarginBasis(next) });
      }
    },
  };
}
