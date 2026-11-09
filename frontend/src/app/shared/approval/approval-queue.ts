import { DestroyRef, Signal, computed, inject, signal } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Params, Router } from '@angular/router';
import { Observable, catchError, map, of } from 'rxjs';
import { PageResponse } from '../models/page';

/** Quantos itens da fila a tela de decisão lê para o contador e a próxima (fila maior conta pelo total do servidor). */
export const APPROVAL_QUEUE_WINDOW = 200;

/** Posição do item aberto na fila filtrada ("2 de 7"); `index` começa em 0. */
export interface QueuePosition {
  index: number;
  total: number;
}

export interface ApprovalQueueOptions {
  /** Endereço da fila (lista) e base da tela de decisão: `<path>/<id>`. */
  path: string;
  /** Filtros da fila que seguem na URL da decisão (os mesmos nomes da lista, ex.: `credor`, `origem`). */
  filterParams: readonly string[];
  /** Itens da fila com os filtros da URL, na ordem do servidor. */
  load: (filters: Params, size: number) => Observable<PageResponse<{ id: string }>>;
  /** Item aberto. */
  currentId: () => string;
  /** A tela foi aberta pela fila (sem isso, não lê a fila: contador e próxima não aparecem). */
  enabled?: () => boolean;
}

/** Navegação entre itens de uma fila de aprovação (contador, próxima e retorno com os filtros). */
export interface ApprovalQueue {
  /** Filtros da fila que vieram na URL (só os permitidos). */
  readonly filters: Signal<Params>;
  /** Posição do item na fila (nulo enquanto carrega, se a fila falhar ou se o item já saiu dela). */
  readonly position: Signal<QueuePosition | null>;
  /** Há outro item na fila além deste. */
  readonly hasNext: Signal<boolean>;
  /** Endereço da fila (`routerLink`; os filtros vão em `filters()` como `queryParams`). */
  readonly path: string;
  /**
   * Depois da decisão: abre o próximo item ainda pendente na mesma fila filtrada (o que vinha depois deste; sem ele, o
   * primeiro que sobrar) ou volta à fila quando ela esvaziou.
   */
  advance(): Promise<void>;
  /** Volta à fila com os filtros. */
  backToQueue(): void;
}

/**
 * Fila de aprovação vista da tela de decisão (padrão único das aprovações, ux.md §17): lê os filtros da URL — os
 * mesmos da lista —, conta a posição do item ("1 de N"), abre a próxima depois de decidir e volta à fila com os
 * filtros. As regras (quem pode aprovar o quê) continuam no servidor: a fila e a decisão são revalidadas lá.
 * Contexto de injeção (campo do componente).
 */
export function approvalQueue(options: ApprovalQueueOptions): ApprovalQueue {
  const route = inject(ActivatedRoute);
  const router = inject(Router);
  let destroyed = false;
  inject(DestroyRef).onDestroy(() => (destroyed = true));
  const allowed = new Set(options.filterParams);
  const enabled = options.enabled ?? (() => true);
  const filters = toSignal(route.queryParamMap.pipe(map((params) => pick(params.keys, (k) => params.get(k), allowed))), {
    initialValue: {} as Params,
  });

  const fetch = (f: Params): Observable<{ ids: string[]; total: number } | null> =>
    options.load(f, APPROVAL_QUEUE_WINDOW).pipe(
      map((page) => ({ ids: page.content.map((i) => i.id), total: page.totalElements })),
      catchError(() => of(null)),
    );
  const queue = rxResource({ params: () => (enabled() ? filters() : undefined), stream: ({ params }) => fetch(params) });
  /** Resultado da última leitura feita por `advance` (a fila sem o item decidido). */
  const fresh = signal<{ ids: string[]; total: number } | null>(null);
  const current = computed(() => fresh() ?? (queue.hasValue() ? queue.value() : null));

  const position = computed<QueuePosition | null>(() => {
    const list = current();
    const index = list ? list.ids.indexOf(options.currentId()) : -1;
    return list && index >= 0 ? { index, total: Math.max(list.total, list.ids.length) } : null;
  });
  const hasNext = computed(() => (current()?.ids ?? []).some((id) => id !== options.currentId()));

  const goToQueue = () => router.navigate([options.path], { queryParams: filters() });
  const backToQueue = () => void goToQueue();

  /** Resolve quando a próxima (ou a fila) abriu — a tela fica ocupada até lá. Fila ilegível: volta à fila. */
  const advance = () =>
    new Promise<void>((resolve) => {
      const before = current()?.ids ?? [];
      const decided = options.currentId();
      const at = before.indexOf(decided);
      // a ordem de antes da decisão: primeiro os que vinham depois, depois os de antes
      const preferred = at >= 0 ? [...before.slice(at + 1), ...before.slice(0, at)] : before.filter((id) => id !== decided);
      fetch(filters()).subscribe((r) => {
        if (destroyed) {
          resolve();
          return;
        }
        const remaining = (r?.ids ?? []).filter((id) => id !== decided);
        const next = preferred.find((id) => remaining.includes(id)) ?? remaining[0];
        if (next) {
          fresh.set(r);
          router.navigate([options.path, next], { queryParams: filters() }).then(() => resolve(), () => resolve());
        } else {
          goToQueue().then(() => resolve(), () => resolve());
        }
      });
    });

  return { filters, position, hasNext, path: options.path, advance, backToQueue };
}

/** Só os parâmetros permitidos e preenchidos. Função pura. */
export function pick(keys: readonly string[], get: (key: string) => string | null, allowed: ReadonlySet<string>): Params {
  const out: Params = {};
  for (const key of keys) {
    const value = get(key);
    if (allowed.has(key) && value) {
      out[key] = value;
    }
  }
  return out;
}

/** Texto do contador: "2 de 7". */
export function positionText(position: QueuePosition | null): string | null {
  return position ? `${position.index + 1} de ${position.total}` : null;
}
