import { Signal, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { distinctUntilChanged, map } from 'rxjs';
import { MarginFilter } from './costing.models';
import {
  MARGIN_QUERY,
  MarginSort,
  formatMarginSort,
  marginFilterParams,
  parseMarginFilter,
  parseMarginSort,
  sameMarginFilter,
} from './margin.rules';

export interface MarginUrlState {
  readonly filter: Signal<MarginFilter>;
  readonly sort: Signal<MarginSort>;
  update(filter: MarginFilter): void;
  setSort(sort: MarginSort): void;
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
    setSort(next: MarginSort) {
      navigate({ [MARGIN_QUERY.sort]: formatMarginSort(next) });
    },
  };
}
