import { Signal, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, ParamMap, Params, Router, convertToParamMap } from '@angular/router';
import { distinctUntilChanged, filter, map, take } from 'rxjs';
import { QUERY, filterParams, parseFilter, sameFilter } from './analytics.filters';
import { AnalyticsFilter } from './analytics.models';

export interface UrlFilter {
  readonly filter: Signal<AnalyticsFilter>;
  update(filter: AnalyticsFilter): void;
}

/**
 * Último filtro usado em qualquer painel (sem o agrupamento, que é de cada painel), só em memória: ao trocar de painel
 * pelo menu, o período e os recortes acompanham o usuário (as abas entre painéis saíram — T10). Guarda ids de credor,
 * carteira, pessoa e equipe, nunca dado pessoal.
 */
let shared: Params | null = null;

/** Esquece o filtro compartilhado (testes). */
export function resetSharedPanelFilter(): void {
  shared = null;
}

/**
 * Estado dos filtros guardado na URL (compartilhável, sobrevive ao recarregar e ao voltar). Painel aberto sem
 * filtro na URL (pelo menu) começa com o último filtro usado nos painéis. Chamar no construtor da página (contexto
 * de injeção).
 */
export function urlFilter(allowedGroupBy: readonly string[] = [], defaultGroupBy: string | null = null): UrlFilter {
  const route = inject(ActivatedRoute);
  const router = inject(Router);
  const parse = (params: ParamMap) => parseFilter(params, new Date(), allowedGroupBy, defaultGroupBy);

  const opened = route.snapshot.queryParamMap;
  const inherit = shared !== null && !opened.has(QUERY.from) && !opened.has(QUERY.to);
  const initial = parse(inherit ? convertToParamMap({ ...shared, [QUERY.groupBy]: opened.get(QUERY.groupBy) }) : opened);
  shared = filterParams({ ...initial, groupBy: null });
  if (inherit) {
    // depois que a navegação que abriu o painel termina (uma navegação nova agora a cancelaria)
    router.events.pipe(filter((e) => e instanceof NavigationEnd), take(1)).subscribe(() => {
      void router.navigate([], { relativeTo: route, queryParams: filterParams(initial), replaceUrl: true, state: { urlState: true } });
    });
  }

  const current = toSignal(
    route.queryParamMap.pipe(
      map((params) => (inherit && !params.has(QUERY.from) ? initial : parse(params))),
      distinctUntilChanged(sameFilter),
    ),
    { initialValue: initial },
  );
  return {
    filter: current,
    update(next: AnalyticsFilter) {
      shared = filterParams({ ...next, groupBy: null });
      if (!sameFilter(next, current())) {
        void router.navigate([], { relativeTo: route, queryParams: filterParams(next), replaceUrl: true, state: { urlState: true } });
      }
    },
  };
}
