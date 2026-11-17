import {
  Signal,
  WritableSignal,
  computed,
  effect,
  inject,
  linkedSignal,
  signal,
  untracked,
} from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, ParamMap } from '@angular/router';
import { map } from 'rxjs';
import { URL_STATE_EMBEDDED, injectUrlStateWriter } from '../data/url-state';
import { sameValue } from '../data/same-value';

/** Linhas por passo do "Mostrar mais" (parecer de UX dos painéis: 25). */
export const SHOW_MORE_STEP = 25;

/** Parâmetro da URL com a quantidade visível (`?qtd=50`); o padrão (um passo) não aparece na URL. */
export const SHOW_MORE_PARAM = 'qtd';

/** Teto aceito da URL: valor maior (adulterado) cai nele; a lista mostra no máximo o que tem. */
export const SHOW_MORE_MAX = 5000;

/**
 * Quantidade visível lida da URL: inteiro entre um passo e {@link SHOW_MORE_MAX}. Ausente, inválido ou menor que um
 * passo → um passo; maior que o teto → o teto. Função pura.
 */
export function parseShowMoreLimit(
  raw: string | null,
  step = SHOW_MORE_STEP,
  max = SHOW_MORE_MAX,
): number {
  if (raw === null || !/^\d{1,9}$/.test(raw)) {
    return step;
  }
  return Math.min(Math.max(Number(raw), step), Math.max(max, step));
}

/** Opções de {@link showMore}. */
export interface ShowMoreOptions {
  /** Linhas por passo (padrão {@link SHOW_MORE_STEP}). */
  readonly step?: number;
  /** Parâmetro da URL (padrão {@link SHOW_MORE_PARAM}). Uma lista por tela usa o padrão. */
  readonly param?: string;
  /**
   * Chave do recorte (filtro, período, agrupamento, busca): quando muda, a lista volta para um passo e o `qtd` sai da
   * URL. Comparada por valor ({@link sameValue}). A ordenação não entra: reordenar mantém a quantidade.
   */
  readonly resetOn?: () => unknown;
  /** `false` guarda a quantidade só em memória (padrão: na URL, salvo sob `appUrlStateEmbedded`). */
  readonly url?: boolean;
}

/** Estado de uma lista com "Mostrar mais" (ver {@link showMore}). */
export interface ShowMore<T> {
  /** Linhas por passo. */
  readonly step: number;
  /** Quantidade pedida (pode passar do total; a lista mostra só o que tem). */
  readonly limit: Signal<number>;
  /** Total de linhas da lista (já ordenada e filtrada pela tela). */
  readonly total: Signal<number>;
  /** Linhas visíveis: as primeiras `limit`, na ordem recebida. */
  readonly visible: Signal<readonly T[]>;
  /** Quantas aparecem agora (`min(limit, total)`). */
  readonly shown: Signal<number>;
  /** Quantas ainda faltam. */
  readonly remaining: Signal<number>;
  /** Quantas o próximo "Mostrar mais" acrescenta (`min(step, remaining)`). */
  readonly next: Signal<number>;
  /** Mostra mais um passo e grava na URL; devolve o índice da primeira linha nova (para levar o foco até ela). */
  more(): number;
  /** Volta para um passo (e tira o `qtd` da URL). */
  reset(): void;
}

/**
 * "Mostrar mais" das tabelas longas dos painéis (ux.md, seção "Tabelas longas nos painéis"): mostra as primeiras 25 linhas e acrescenta 25 a cada
 * pedido, sem paginação nem rolagem interna. A quantidade fica na URL (`qtd`, com `replaceUrl`, preservando os demais
 * parâmetros) para o "voltar" e o recarregar manterem o contexto; trocar o recorte (`resetOn`) volta para 25.
 * A ordenação da tela vale sobre a lista inteira: entregue `rows` já ordenadas — o corte vem depois.
 *
 * Contexto de injeção (campo do componente). Use com `app-show-more`, que mostra a contagem, o botão e leva o foco
 * para a primeira linha nova.
 *
 * ```ts
 * protected readonly table = showMore(() => this.report()?.rows ?? [], { resetOn: () => this.state.filter() });
 * ```
 * ```html
 * @for (row of table.visible(); track row.key) { <tr>…</tr> }
 * <app-show-more [list]="table" [table]="tableEl" singular="pessoa" plural="pessoas" />
 * ```
 */
export function showMore<T>(rows: () => readonly T[], options: ShowMoreOptions = {}): ShowMore<T> {
  const step = Math.max(1, Math.floor(options.step ?? SHOW_MORE_STEP));
  const param = options.param ?? SHOW_MORE_PARAM;
  const useUrl = options.url !== false && !inject(URL_STATE_EMBEDDED, { optional: true });

  let limit: WritableSignal<number>;
  let write: (value: number) => void;
  if (useUrl) {
    const route = inject(ActivatedRoute);
    const writer = injectUrlStateWriter();
    const parse = (params: ParamMap) => parseShowMoreLimit(params.get(param), step);
    const fromUrl = toSignal(route.queryParamMap.pipe(map(parse)), {
      initialValue: parse(route.snapshot.queryParamMap),
    });
    // segue a URL (link, "voltar"); entre um pedido e a gravação, vale o valor local
    limit = linkedSignal(() => fromUrl());
    write = (value) => writer.write({ [param]: value <= step ? null : String(value) });
  } else {
    limit = signal(step);
    write = () => undefined;
  }

  const total = computed(() => rows().length);
  const shown = computed(() => Math.min(limit(), total()));
  const reset = () => {
    limit.set(step);
    write(step);
  };

  if (options.resetOn) {
    const key = options.resetOn;
    let previous: { value: unknown } | null = null;
    effect(() => {
      const current = key();
      if (previous && !sameValue(previous.value, current)) {
        untracked(reset);
      }
      previous = { value: current };
    });
  }

  return {
    step,
    limit: limit.asReadonly(),
    total,
    visible: computed(() => {
      const all = rows();
      return all.length > limit() ? all.slice(0, limit()) : all;
    }),
    shown,
    remaining: computed(() => Math.max(0, total() - limit())),
    next: computed(() => Math.min(step, Math.max(0, total() - limit()))),
    more() {
      const first = shown();
      // a partir do que aparece (um `qtd` acima do total não acumula passos invisíveis)
      const value = Math.min(first + step, SHOW_MORE_MAX);
      limit.set(value);
      write(value);
      return first;
    },
    reset,
  };
}
