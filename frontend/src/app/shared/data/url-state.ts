import { DestroyRef, Directive, InjectionToken, Signal, inject, linkedSignal, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormGroup } from '@angular/forms';
import { ActivatedRoute, ParamMap, Params, Router } from '@angular/router';
import { debounceTime, map } from 'rxjs';
import { sameValue } from './same-value';

/**
 * Estado de tela guardado na URL (filtros, página, ordenação, aba): sobrevive ao voltar, ao recarregar e pode ser
 * compartilhado. Generaliza o `url-filter` dos painéis para todas as listas (ux.md §16).
 *
 * Regras:
 * - parâmetros em português e curtos (`credor`, `situacao`, `pagina`, `ordem`, `aba`), sempre com `replaceUrl`
 *   (filtrar não cria entrada no histórico: "voltar" sai da tela, não desfaz um filtro);
 * - valor igual ao padrão da tela não vai para a URL (endereço limpo ao abrir pelo menu);
 * - nunca dado pessoal na URL: devedor é filtrado pelo id (o CPF/CNPJ fica no corpo de `POST /debtors/lookup`) e
 *   nenhuma busca por pessoa (devedor, usuário, titular: nome, documento, e-mail, telefone, IP) é mapeada; texto livre
 *   só em buscas por cadastro sem dado pessoal (nome de carteira, código de template);
 * - valor inválido ou adulterado na URL cai no padrão (o servidor continua validando tudo).
 */

/** Conversão de um valor de tela ↔ parâmetro da URL. `parse` devolve `undefined` quando o texto não é válido. */
export interface UrlCodec<T> {
  readonly param: string;
  parse(raw: string): T | undefined;
  format(value: T): string | null;
}

const ID = /^[A-Za-z0-9_-]{1,64}$/;
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/;

/** Codecs dos tipos usados nos filtros das listas. */
export const urlParam = {
  /** Texto (busca, seleção de opção). Limitado a 200 caracteres. */
  text(param: string): UrlCodec<string> {
    return { param, parse: (raw) => (raw.length <= 200 ? raw : undefined), format: (v) => (v === '' ? null : v) };
  },
  /** Uma das opções permitidas (seleções com lista fechada). */
  choice<T extends string>(param: string, allowed: readonly T[]): UrlCodec<T> {
    return { param, parse: (raw) => (raw === '' || allowed.includes(raw as T) ? (raw as T) : undefined), format: (v) => v || null };
  },
  /** Um id (seleção de fila, devedor vindo de outra tela) ou vazio. */
  id(param: string): UrlCodec<string> {
    return { param, parse: (raw) => (ID.test(raw) ? raw : undefined), format: (v) => v || null };
  },
  /** Lista de ids (seletores de entidade): `credor=a,b`. */
  ids(param: string): UrlCodec<string[]> {
    return {
      param,
      parse: (raw) => {
        const ids = raw.split(',').filter(Boolean);
        return ids.length <= 50 && ids.every((id) => ID.test(id)) ? ids : undefined;
      },
      format: (v) => (v.length ? v.join(',') : null),
    };
  },
  /** Caixa de seleção: `1`/`0`. */
  flag(param: string): UrlCodec<boolean> {
    return { param, parse: (raw) => (raw === '1' ? true : raw === '0' ? false : undefined), format: (v) => (v ? '1' : '0') };
  },
  /** Data local `yyyy-MM-dd` (datepicker) ou vazio. */
  date(param: string): UrlCodec<string | null> {
    return { param, parse: (raw) => (ISO_DATE.test(raw) ? raw : undefined), format: (v) => v || null };
  },
  /** Inteiro não negativo (ou nulo). */
  int(param: string): UrlCodec<number | null> {
    return {
      param,
      parse: (raw) => (/^\d{1,9}$/.test(raw) ? Number(raw) : undefined),
      format: (v) => (v === null || v === undefined || Number.isNaN(v) ? null : String(v)),
    };
  },
};

export type UrlCodecs<V> = { readonly [K in keyof V]?: UrlCodec<V[K]> };

/**
 * Valores da URL para os campos mapeados: parâmetro ausente ou inválido → padrão da tela.
 * Função pura (testável sem o roteador).
 */
export function readUrlValues<V extends object>(params: ParamMap, codecs: UrlCodecs<V>, defaults: V): V {
  const out = { ...defaults };
  for (const key of Object.keys(codecs) as (keyof V)[]) {
    const codec = codecs[key]!;
    const raw = params.get(codec.param);
    const parsed = raw === null ? undefined : codec.parse(raw);
    out[key] = parsed === undefined ? defaults[key] : parsed;
  }
  return out;
}

/** Parâmetros a gravar: valor igual ao padrão sai da URL (`null`). Função pura. */
export function urlParamsOf<V extends object>(value: V, codecs: UrlCodecs<V>, defaults: V): Params {
  const params: Params = {};
  for (const key of Object.keys(codecs) as (keyof V)[]) {
    const codec = codecs[key]!;
    // valor vazio diferente do padrão ("Todas" quando o padrão é "Ativos") fica como `param=` para não voltar ao padrão
    params[codec.param] = sameValue(value[key], defaults[key]) ? null : (codec.format(value[key]) ?? '');
  }
  return params;
}

/**
 * Gravação coalescida dos parâmetros de uma tela: várias fontes (filtros, página, ordem, aba) que mudam no mesmo
 * ciclo (inclusive os efeitos) viram uma navegação só, com `replaceUrl`. Um escritor por rota ativada (`ActivatedRoute`): todos os
 * componentes da mesma tela compartilham a fila. Enquanto a navegação de uma gravação não termina, os parâmetros dela
 * continuam na fila (uma gravação seguinte não os perde ao mesclar com a URL ainda antiga). Gravação pedida durante a
 * navegação de um link ou do "voltar", ou depois que a rota deixou de estar ativa (o usuário saiu da tela), é
 * descartada — nunca cancela a saída da tela.
 */
export class UrlStateWriter {
  private pending: Params = {};
  private inflight: Params = {};
  private scheduled = false;
  private generation = 0;

  constructor(
    private readonly router: Router,
    private readonly route: ActivatedRoute,
  ) {}

  write(params: Params): void {
    this.pending = { ...this.pending, ...params };
    if (!this.scheduled) {
      this.scheduled = true;
      // próxima tarefa (não microtarefa): pega também o que os efeitos do mesmo ciclo gravarem (página, ordem)
      setTimeout(() => this.flush());
    }
  }

  private flush(): void {
    this.scheduled = false;
    const params = { ...this.inflight, ...this.pending };
    this.pending = {};
    const navigation = this.router.currentNavigation();
    const foreign = navigation && (navigation.trigger === 'popstate' || !navigation.extras.state?.['urlState']);
    if (foreign || !isActiveRoute(this.router, this.route)) {
      this.inflight = {};
      return;
    }
    const current = this.route.snapshot.queryParamMap;
    const changed = Object.entries(params).some(([key, value]) => (current.get(key) ?? null) !== (value ?? null));
    if (!changed) {
      this.inflight = {};
      return;
    }
    this.inflight = params;
    const generation = ++this.generation;
    const settle = () => {
      if (generation === this.generation) {
        this.inflight = {};
      }
    };
    void this.router
      .navigate([], { relativeTo: this.route, queryParams: params, queryParamsHandling: 'merge', replaceUrl: true, state: { urlState: true } })
      .then(settle, settle);
  }
}

/** A rota ainda faz parte da árvore de rotas ativa (o usuário não saiu da tela). */
function isActiveRoute(router: Router, route: ActivatedRoute): boolean {
  const stack: ActivatedRoute[] = [router.routerState.root];
  while (stack.length) {
    const next = stack.pop()!;
    if (next === route) {
      return true;
    }
    stack.push(...next.children);
  }
  return false;
}

const WRITERS = new WeakMap<ActivatedRoute, UrlStateWriter>();

/**
 * Escritor de estado na URL da tela atual: um por rota ativada, para que filtros, página e aba da mesma tela
 * coalesçam numa navegação só. Contexto de injeção.
 */
export function injectUrlStateWriter(): UrlStateWriter {
  const route = inject(ActivatedRoute);
  let writer = WRITERS.get(route);
  if (!writer) {
    writer = new UrlStateWriter(inject(Router), route);
    WRITERS.set(route, writer);
  }
  return writer;
}

/** Valores padrão (antes da URL) de cada formulário ligado por `syncFormWithUrl`: base da contagem de filtros ativos. */
const FORM_DEFAULTS = new WeakMap<FormGroup, object>();

/** Padrão da tela para o formulário (o valor de antes de aplicar a URL), ou `null` se ele não foi ligado à URL. */
export function urlFormDefaults<V extends object>(form: FormGroup): V | null {
  return (FORM_DEFAULTS.get(form) as V | undefined) ?? null;
}

/**
 * Liga um formulário de filtros à URL: lê os parâmetros ao abrir (antes de a consulta ser criada), grava cada mudança
 * (com o mesmo atraso dos filtros) e acompanha mudanças externas da URL (link com outros filtros para a mesma tela).
 * Devolve o próprio formulário, para uso direto na declaração do campo.
 *
 * ```ts
 * protected readonly filters = syncFormWithUrl(this.fb.group({ creditor: this.fb.control<string[]>([]), status: [''] }),
 *   { creditor: urlParam.ids('credor'), status: urlParam.text('situacao') });
 * ```
 */
export function syncFormWithUrl<G extends FormGroup>(
  form: G,
  codecs: UrlCodecs<ReturnType<G['getRawValue']>>,
  debounceMs = 250,
): G {
  type V = ReturnType<G['getRawValue']>;
  const route = inject(ActivatedRoute);
  const router = inject(Router);
  const writer = injectUrlStateWriter();
  const destroyRef = inject(DestroyRef);
  const defaults = form.getRawValue() as V;
  FORM_DEFAULTS.set(form, defaults);
  const mapped = codecs as UrlCodecs<V>;

  const fromUrl = (params: ParamMap) => readUrlValues(params, mapped, defaults);
  const picked = (value: V) => {
    const out: Partial<V> = {};
    for (const key of Object.keys(mapped) as (keyof V)[]) {
      out[key] = value[key];
    }
    return out;
  };

  form.patchValue(picked(fromUrl(route.snapshot.queryParamMap)) as never, { emitEvent: false });

  const changes = form.valueChanges.pipe(debounceTime(debounceMs)).subscribe(() => {
    writer.write(urlParamsOf(form.getRawValue() as V, mapped, defaults));
  });
  const external = route.queryParamMap.subscribe((params) => {
    if (isUrlStateNavigation(router)) {
      return; // eco de uma gravação desta tela (o formulário já tem o valor, talvez até mais novo)
    }
    const next = fromUrl(params);
    if (!sameValue(picked(next), picked(form.getRawValue() as V))) {
      form.patchValue(picked(next) as never);
    }
  });
  destroyRef.onDestroy(() => {
    changes.unsubscribe();
    external.unsubscribe();
  });
  return form;
}

/** A navegação em curso (ou a última concluída) é uma gravação de estado de tela, não um link. */
export function isUrlStateNavigation(router: Router): boolean {
  const navigation = router.currentNavigation() ?? router.lastSuccessfulNavigation();
  // no "voltar" o estado da entrada é reposto: não é uma gravação desta tela
  return navigation?.trigger !== 'popstate' && !!navigation?.extras.state?.['urlState'];
}

export interface UrlTab<T extends string> {
  readonly value: Signal<T>;
  set(tab: T): void;
}

/**
 * Tela embutida em outra (ex.: ficha do devedor no painel lateral da estação): a aba fica só em memória e não lê nem
 * grava o `?aba=` da tela hospedeira, que tem as próprias abas na URL (ux.md §16).
 */
export const URL_STATE_EMBEDDED = new InjectionToken<boolean>('URL_STATE_EMBEDDED');

/** Marca o componente do mesmo elemento (e os filhos) como embutido: `<app-debtor-detail appUrlStateEmbedded … />`. */
@Directive({ selector: '[appUrlStateEmbedded]', providers: [{ provide: URL_STATE_EMBEDDED, useValue: true }] })
export class UrlStateEmbedded {}

/**
 * Aba ativa guardada na URL (`?aba=`), com `replaceUrl`: voltar para a tela reabre a mesma aba. Valor ausente ou
 * fora das opções → aba padrão (que não aparece na URL). Link externo com outra aba para a mesma tela também troca.
 * Sob `appUrlStateEmbedded`, a aba fica só em memória.
 */
export function urlTab<T extends string>(allowed: readonly T[], fallback: T, param = 'aba'): UrlTab<T> {
  if (inject(URL_STATE_EMBEDDED, { optional: true })) {
    const local = signal<T>(fallback);
    return {
      value: local.asReadonly(),
      set(tab: T) {
        if (allowed.includes(tab)) {
          local.set(tab);
        }
      },
    };
  }
  const route = inject(ActivatedRoute);
  const writer = injectUrlStateWriter();
  const parse = (params: ParamMap): T => {
    const raw = params.get(param) as T | null;
    return raw !== null && allowed.includes(raw) ? raw : fallback;
  };
  const fromUrl = toSignal(route.queryParamMap.pipe(map(parse)), { initialValue: parse(route.snapshot.queryParamMap) });
  const value = linkedSignal(() => fromUrl());
  return {
    value: value.asReadonly(),
    set(tab: T) {
      if (!allowed.includes(tab) || tab === value()) {
        return;
      }
      value.set(tab);
      writer.write({ [param]: tab === fallback ? null : tab });
    },
  };
}
