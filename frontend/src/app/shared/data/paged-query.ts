import { DestroyRef, Signal, WritableSignal, computed, effect, inject, linkedSignal, untracked } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { ActivatedRoute, ParamMap, Router } from '@angular/router';
import { Observable } from 'rxjs';
import { TablePage, TableSort } from '../components/data-table/data-table';
import { DEFAULT_PAGE_SIZE, PageRequest, PageResponse, clampPageSize, emptyPage } from '../models/page';
import { sameValue } from './same-value';
import { injectUrlStateWriter, isUrlStateNavigation } from './url-state';

export interface PagedQuery<T> {
  /** Página corrente; volta para a primeira sempre que o filtro (ou a ordenação) muda. */
  readonly page: WritableSignal<PageRequest>;
  /** Ordenação por coluna escolhida na tabela (nula = a padrão do servidor). */
  readonly sort: Signal<TableSort | null>;
  readonly rows: Signal<T[]>;
  readonly tablePage: Signal<TablePage>;
  readonly loading: Signal<boolean>;
  readonly failed: Signal<boolean>;
  reload(): void;
  setPage(request: PageRequest): void;
  setSort(sort: TableSort | null): void;
}

/**
 * Ordenação por coluna: chave da coluna da tabela → nome do campo aceito pelo servidor (lista branca do caso de uso,
 * `shared.paging.SortableFields`). Coluna fora do mapa não ordena.
 */
export type SortFields = Readonly<Record<string, string>>;

export interface PagedQueryOptions {
  initialSize?: number;
  /**
   * Página, tamanho e ordenação na URL (`pagina`, `tamanho`, `ordem`), com `replaceUrl`: voltar para a lista reabre a
   * mesma página. Use na lista principal de uma tela; tabelas secundárias (abas de ficha) só com `prefix`
   * (`<prefixo>-pagina`), e nunca em diálogos (não têm rota própria).
   */
  url?: boolean | { prefix: string };
  /** Colunas ordenáveis (ver `SortFields`). */
  sortFields?: SortFields;
}

/** Janela em que a página de um link externo sobrevive à chegada dos filtros do mesmo link (atraso do formulário). */
const EXTERNAL_WINDOW_MS = 1000;

const SORT_PARAM = /^(-?)([A-Za-z][A-Za-z0-9]{0,39})$/;

/** `ordem=-saldo` ↔ coluna `saldo` decrescente. Coluna fora das ordenáveis → sem ordenação. Função pura. */
export function parseSortParam(raw: string | null, sortFields: SortFields | undefined): TableSort | null {
  const match = raw ? SORT_PARAM.exec(raw) : null;
  if (!match || !sortFields || !Object.hasOwn(sortFields, match[2])) {
    return null;
  }
  return { column: match[2], direction: match[1] ? 'desc' : 'asc' };
}

export function formatSortParam(sort: TableSort | null): string | null {
  return sort ? `${sort.direction === 'desc' ? '-' : ''}${sort.column}` : null;
}

/** `sort` aceito pelo servidor (`campo,asc|desc`) para a ordenação da tabela. Função pura. */
export function serverSort(sort: TableSort | null, sortFields: SortFields | undefined): string | undefined {
  const field = sort && sortFields && Object.hasOwn(sortFields, sort.column) ? sortFields[sort.column] : undefined;
  return field ? `${field},${sort!.direction}` : undefined;
}

/** Página (1 em diante na URL) e tamanho lidos da URL; ausentes ou inválidos → primeira página e tamanho padrão. */
export function readPageParams(params: ParamMap, prefix: string, initialSize: number): PageRequest {
  const page = Number(params.get(prefix + 'pagina'));
  const size = Number(params.get(prefix + 'tamanho'));
  return {
    page: Number.isInteger(page) && page >= 1 && page <= 100_000 ? page - 1 : 0,
    size: Number.isInteger(size) && size >= 1 ? clampPageSize(size) : initialSize,
  };
}

/**
 * Consulta paginada no servidor (`PageResponse`) dirigida por signals: recarrega ao mudar filtro, página ou ordenação
 * e mantém as linhas anteriores durante o carregamento. Página, tamanho e ordenação ficam na URL (opção `url`). A
 * ordenação vai ao servidor em `PageRequest.sort` (o `loader` repassa ao endpoint). Deve ser criada em contexto de
 * injeção (campo de componente).
 */
export function pagedQuery<F, T>(
  filter: Signal<F>,
  loader: (filter: F, page: PageRequest) => Observable<PageResponse<T>>,
  options: number | PagedQueryOptions = {},
): PagedQuery<T> {
  const opts: PagedQueryOptions = typeof options === 'number' ? { initialSize: options } : options;
  const initialSize = opts.initialSize ?? DEFAULT_PAGE_SIZE;
  const urlEnabled = !!opts.url;
  const prefix = typeof opts.url === 'object' ? `${opts.url.prefix}-` : '';
  const route = urlEnabled ? inject(ActivatedRoute, { optional: true }) : null;
  const writer = route ? injectUrlStateWriter() : null;
  const initialParams = route?.snapshot.queryParamMap ?? null;

  const initialSort = initialParams ? parseSortParam(initialParams.get(prefix + 'ordem'), opts.sortFields) : null;
  const sort = linkedSignal<TableSort | null>(() => initialSort);

  let first = true;
  /**
   * Página vinda de uma mudança externa da URL (link para a mesma lista): vale também para o recálculo causado pelos
   * filtros do mesmo link, que chegam depois do atraso do formulário (até `EXTERNAL_WINDOW_MS`).
   */
  let external: PageRequest | null = null;
  let externalTimer: ReturnType<typeof setTimeout> | undefined;
  const page = linkedSignal<{ filter: F; sort: TableSort | null }, PageRequest>({
    source: () => ({ filter: filter(), sort: sort() }),
    computation: (_, previous) => {
      if (!previous && first && initialParams) {
        first = false;
        return readPageParams(initialParams, prefix, initialSize);
      }
      first = false;
      return external ?? { page: 0, size: previous?.value.size ?? initialSize };
    },
  });
  const clearExternal = () => {
    external = null;
    clearTimeout(externalTimer);
  };

  if (route && urlEnabled) {
    // link externo para a mesma lista (outra página ou ordem); gravações desta tela e o "voltar" já trazem o estado
    const router = inject(Router);
    const subscription = route.queryParamMap.subscribe((params) => {
      if (isUrlStateNavigation(router)) {
        return;
      }
      const nextSort = parseSortParam(params.get(prefix + 'ordem'), opts.sortFields);
      const nextPage = readPageParams(params, prefix, initialSize);
      const current = untracked(page);
      if (sameValue(nextSort, untracked(sort)) && nextPage.page === current.page && nextPage.size === current.size) {
        return;
      }
      clearExternal();
      external = nextPage;
      externalTimer = setTimeout(() => (external = null), EXTERNAL_WINDOW_MS);
      sort.set(nextSort);
      untracked(page);
      page.set(nextPage);
    });
    inject(DestroyRef).onDestroy(() => {
      subscription.unsubscribe();
      clearExternal();
    });
  }

  const resource = rxResource({
    params: () => ({ filter: filter(), page: page(), sort: sort() }),
    stream: ({ params }) => {
      const request: PageRequest = { ...params.page };
      const sortParam = serverSort(params.sort, opts.sortFields);
      if (sortParam) {
        request.sort = sortParam;
      }
      return loader(params.filter, request);
    },
  });

  const data = linkedSignal<PageResponse<T> | undefined, PageResponse<T>>({
    source: () => (resource.hasValue() ? resource.value() : undefined),
    computation: (value, previous) =>
      value ?? (resource.status() === 'error' ? emptyPage<T>() : (previous?.value ?? emptyPage<T>())),
  });

  if (writer) {
    let initial = true;
    effect(() => {
      const p = page();
      const s = sort();
      if (initial) {
        initial = false; // o estado inicial veio da URL (ou é o padrão): nada a gravar
        return;
      }
      untracked(() =>
        writer.write({
          [prefix + 'pagina']: p.page > 0 ? String(p.page + 1) : null,
          [prefix + 'tamanho']: p.size !== initialSize ? String(p.size) : null,
          [prefix + 'ordem']: formatSortParam(s),
        }),
      );
    });
  }

  return {
    page,
    sort: sort.asReadonly(),
    rows: computed(() => data().content),
    tablePage: computed(() => ({ index: page().page, size: page().size, total: data().totalElements })),
    loading: resource.isLoading,
    failed: computed(() => resource.status() === 'error'),
    reload: () => resource.reload(),
    setPage: (request) => {
      clearExternal();
      page.set({ page: Math.max(request.page, 0), size: clampPageSize(request.size) });
    },
    setSort: (next) => {
      clearExternal();
      sort.set(next && opts.sortFields && Object.hasOwn(opts.sortFields, next.column) ? next : null);
    },
  };
}
