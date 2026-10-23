import { Signal, computed, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { Observable, catchError, forkJoin, map, of, tap } from 'rxjs';
import { Option } from '../models/lookup';

/** Rótulo exibido quando o registro não pode ser resolvido (fora do escopo, removido ou sem permissão). */
export const UNRESOLVED_NAME = 'Indisponível';

/** Resolução de rótulos por id (as fontes de `app-entity-picker` também servem). */
export interface NameSource {
  resolve(ids: string[]): Observable<Option[]>;
}

export interface NameMap {
  /** Nome conhecido do id; `…` enquanto carrega; vazio para id nulo. */
  name(id: string | null | undefined): string;
  readonly names: Signal<ReadonlyMap<string, string>>;
}

export interface NameMapOptions {
  /**
   * Resolve até N ids por chamada (fontes com consulta em lote, ex.: nomes de usuários). Ids
   * ausentes da resposta ficam como {@link UNRESOLVED_NAME}. Sem a opção, um id por chamada.
   */
  batch?: number;
}

/**
 * Resolve nomes de ids exibidos em listas (credores, carteiras, devedores, usuários) sob demanda,
 * acumulando os já conhecidos para não repetir requisições ao paginar. Sem lote, cada id é
 * resolvido separadamente: um registro inacessível não impede a exibição dos demais. Deve ser
 * criado em contexto de injeção (campo de componente).
 */
export function nameMap(source: NameSource, ids: () => readonly (string | null | undefined)[], options: NameMapOptions = {}): NameMap {
  const known = signal<ReadonlyMap<string, string>>(new Map());

  const missing = computed(
    () => {
      const current = known();
      return [...new Set(ids().filter((id): id is string => !!id && !current.has(id)))].sort();
    },
    { equal: (a, b) => a.length === b.length && a.every((id, i) => id === b[i]) },
  );

  const size = Math.max(1, options.batch ?? 1);
  const chunk = (list: string[]) => Array.from({ length: Math.ceil(list.length / size) }, (_, i) => list.slice(i * size, (i + 1) * size));

  rxResource({
    params: () => (missing().length ? missing() : undefined),
    stream: ({ params }) =>
      forkJoin(
        chunk(params).map((group) =>
          source.resolve(group).pipe(
            catchError(() => of([])),
            map((found) =>
              group.map((id) => [id, (found.find((o) => o.value === id) ?? (group.length === 1 ? found[0] : undefined))?.label ?? UNRESOLVED_NAME] as const),
            ),
          ),
        ),
      ).pipe(tap((groups) => known.update((current) => new Map([...current, ...groups.flat()])))),
  });

  return {
    names: known.asReadonly(),
    name: (id) => (id ? (known().get(id) ?? '…') : ''),
  };
}
