import { Signal, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import { distinctUntilChanged, map } from 'rxjs';
import { filterParams, parseFilter, sameFilter } from './analytics.filters';
import { AnalyticsFilter } from './analytics.models';

export interface UrlFilter {
  readonly filter: Signal<AnalyticsFilter>;
  update(filter: AnalyticsFilter): void;
}

/**
 * Estado dos filtros guardado na URL (compartilhável, sobrevive ao recarregar e ao voltar). Chamar
 * no construtor da página (contexto de injeção).
 */
export function urlFilter(allowedGroupBy: readonly string[] = [], defaultGroupBy: string | null = null): UrlFilter {
  const route = inject(ActivatedRoute);
  const router = inject(Router);
  const read = () => parseFilter(route.snapshot.queryParamMap, new Date(), allowedGroupBy, defaultGroupBy);
  const filter = toSignal(
    route.queryParamMap.pipe(
      map((params) => parseFilter(params, new Date(), allowedGroupBy, defaultGroupBy)),
      distinctUntilChanged(sameFilter),
    ),
    { initialValue: read() },
  );
  return {
    filter,
    update(next: AnalyticsFilter) {
      if (!sameFilter(next, filter())) {
        void router.navigate([], { relativeTo: route, queryParams: filterParams(next), replaceUrl: true });
      }
    },
  };
}
