import { Signal, WritableSignal, computed, linkedSignal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Observable } from 'rxjs';
import { TablePage } from '../components/data-table/data-table';
import { DEFAULT_PAGE_SIZE, PageRequest, PageResponse, clampPageSize, emptyPage } from '../models/page';

export interface PagedQuery<T> {
  /** Página corrente; volta para a primeira sempre que o filtro muda. */
  readonly page: WritableSignal<PageRequest>;
  readonly rows: Signal<T[]>;
  readonly tablePage: Signal<TablePage>;
  readonly loading: Signal<boolean>;
  readonly failed: Signal<boolean>;
  reload(): void;
  setPage(request: PageRequest): void;
}

/**
 * Consulta paginada no servidor (`PageResponse`) dirigida por signals: recarrega ao mudar filtro
 * ou página e mantém as linhas anteriores durante o carregamento. Deve ser criada em contexto de
 * injeção (campo de componente).
 */
export function pagedQuery<F, T>(
  filter: Signal<F>,
  loader: (filter: F, page: PageRequest) => Observable<PageResponse<T>>,
  initialSize = DEFAULT_PAGE_SIZE,
): PagedQuery<T> {
  const page = linkedSignal<F, PageRequest>({
    source: filter,
    computation: (_, previous) => ({ page: 0, size: previous?.value.size ?? initialSize }),
  });

  const resource = rxResource({
    params: () => ({ filter: filter(), page: page() }),
    stream: ({ params }) => loader(params.filter, params.page),
  });

  const data = linkedSignal<PageResponse<T> | undefined, PageResponse<T>>({
    source: () => (resource.hasValue() ? resource.value() : undefined),
    computation: (value, previous) =>
      value ?? (resource.status() === 'error' ? emptyPage<T>() : (previous?.value ?? emptyPage<T>())),
  });

  return {
    page,
    rows: computed(() => data().content),
    tablePage: computed(() => ({ index: page().page, size: page().size, total: data().totalElements })),
    loading: resource.isLoading,
    failed: computed(() => resource.status() === 'error'),
    reload: () => resource.reload(),
    setPage: (request) => page.set({ page: Math.max(request.page, 0), size: clampPageSize(request.size) }),
  };
}
