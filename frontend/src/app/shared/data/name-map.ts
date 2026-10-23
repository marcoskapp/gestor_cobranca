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

/**
 * Resolve nomes de ids exibidos em listas (credores, carteiras, devedores, usuários) sob demanda,
 * acumulando os já conhecidos para não repetir requisições ao paginar. Cada id é resolvido
 * separadamente: um registro inacessível não impede a exibição dos demais. Deve ser criado em
 * contexto de injeção (campo de componente).
 */
export function nameMap(source: NameSource, ids: () => readonly (string | null | undefined)[]): NameMap {
  const known = signal<ReadonlyMap<string, string>>(new Map());

  const missing = computed(
    () => {
      const current = known();
      return [...new Set(ids().filter((id): id is string => !!id && !current.has(id)))].sort();
    },
    { equal: (a, b) => a.length === b.length && a.every((id, i) => id === b[i]) },
  );

  rxResource({
    params: () => (missing().length ? missing() : undefined),
    stream: ({ params }) =>
      forkJoin(
        params.map((id) =>
          source.resolve([id]).pipe(
            map((options) => options[0]?.label ?? UNRESOLVED_NAME),
            catchError(() => of(UNRESOLVED_NAME)),
            map((label) => [id, label] as const),
          ),
        ),
      ).pipe(tap((entries) => known.update((current) => new Map([...current, ...entries])))),
  });

  return {
    names: known.asReadonly(),
    name: (id) => (id ? (known().get(id) ?? '…') : ''),
  };
}
