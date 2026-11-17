import { Signal, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, ParamMap, Params, Router, convertToParamMap } from '@angular/router';
import { distinctUntilChanged, filter, map, take } from 'rxjs';
import { QUERY, filterParams, parseFilter, sameFilter } from './analytics.filters';
import { AnalyticsFilter } from './analytics.models';

export interface UrlFilter {
  readonly filter: Signal<AnalyticsFilter>;
  update(filter: AnalyticsFilter): void;
  /**
   * Filtra o painel por um item de um gráfico (clique na barra): entra no histórico, então o "voltar" desfaz. O
   * {@code label} ("Credor: Banco X") aparece no chip do filtro enquanto o filtro for o mesmo.
   */
  focus(filter: AnalyticsFilter, label: string, dimension: string): void;
  /** Rótulo do filtro aplicado pelo gráfico, enquanto a dimensão continuar só com aquele item (nulo ao mudar ou voltar). */
  readonly focused: Signal<string | null>;
}

/** Dimensões dos gráficos que existem na barra de filtros (o clique na barra filtra por elas). */
const FOCUS_FIELDS: Record<string, keyof Pick<AnalyticsFilter, 'creditorIds' | 'portfolioIds' | 'operatorIds' | 'teamIds' | 'channels'>> = {
  CREDITOR: 'creditorIds',
  PORTFOLIO: 'portfolioIds',
  OPERATOR: 'operatorIds',
  TEAM: 'teamIds',
  CHANNEL: 'channels',
};

/** Chave do grupo "sem carteira/sem negociador/não informado" (`AnalyticsQueryService.NO_KEY`): não existe nos filtros. */
export const NO_GROUP_KEY = '-';

/** O item do gráfico pode filtrar o painel? (o grupo "sem …" e a barra "Outros" não têm filtro equivalente) */
export function focusableKey(key: string): boolean {
  return key !== NO_GROUP_KEY && !key.startsWith('__');
}

/** A dimensão do agrupamento pode filtrar o painel (credor, carteira, pessoa, equipe ou canal)? */
export function focusable(dimension: string | null | undefined): boolean {
  return !!dimension && dimension in FOCUS_FIELDS;
}

/** Filtro com o item escolhido no gráfico (substitui a seleção daquela dimensão); nulo para dimensão sem filtro. */
export function focusFilter(current: AnalyticsFilter, dimension: string | null | undefined, key: string): AnalyticsFilter | null {
  const field = dimension ? FOCUS_FIELDS[dimension] : undefined;
  return field && focusableKey(key) ? { ...current, [field]: [key] } : null;
}

/** O mesmo filtro sem a dimensão escolhida no gráfico (remover o chip). */
export function unfocusFilter(current: AnalyticsFilter, dimension: string | null | undefined): AnalyticsFilter {
  const field = dimension ? FOCUS_FIELDS[dimension] : undefined;
  return field ? { ...current, [field]: [] } : current;
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
  const focusState = signal<{ field: keyof AnalyticsFilter; key: string; label: string } | null>(null);
  return {
    filter: current,
    // o chip fica enquanto a dimensão continuar só com o item clicado (trocar o período não esconde o recorte)
    focused: computed(() => {
      const f = focusState();
      const value = f ? (current()[f.field] as string[]) : null;
      return f && value?.length === 1 && value[0] === f.key ? f.label : null;
    }),
    focus(next: AnalyticsFilter, label: string, dimension: string) {
      shared = filterParams({ ...next, groupBy: null });
      const field = FOCUS_FIELDS[dimension];
      const value = field ? (next[field] as string[]) : [];
      focusState.set(field && value.length === 1 ? { field, key: value[0], label } : null);
      if (!sameFilter(next, current())) {
        void router.navigate([], { relativeTo: route, queryParams: filterParams(next), state: { urlState: true } });
      }
    },
    update(next: AnalyticsFilter) {
      shared = filterParams({ ...next, groupBy: null });
      if (!sameFilter(next, current())) {
        void router.navigate([], { relativeTo: route, queryParams: filterParams(next), replaceUrl: true, state: { urlState: true } });
      }
    },
  };
}
