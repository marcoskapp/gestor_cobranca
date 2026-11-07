import { DOCUMENT, Location } from '@angular/common';
import { Injectable, Signal, computed, effect, inject, signal, untracked } from '@angular/core';
import { AuthService } from '../../auth/auth.service';
import { NavigationCancel, NavigationEnd, NavigationError, NavigationStart, Router, TitleStrategy, UrlTree } from '@angular/router';

/** Uma entrada do histórico do navegador dentro do app (o caminho percorrido pelo usuário). */
export interface HistoryEntry {
  /** Chave da entrada (`history.state.navigationId` do roteador). */
  readonly key: number;
  /** Endereço completo, com os filtros da tela (estado na URL). */
  readonly url: string;
  readonly path: string;
  /** Nome da tela ou do registro ("Casos de cobrança", "Banco Alfa S.A."). */
  readonly label: string;
  /** Entrada de onde o usuário veio (a anterior no histórico), se for do app. */
  readonly previous: number | null;
}

/** Caminho sem consulta nem fragmento, sem barra final. */
export function pathOf(url: string): string {
  return url.split(/[?#]/, 1)[0].replace(/\/+$/, '') || '/';
}

const RESTORE_TIMEOUT_MS = 4000;
/** Entradas do histórico e telas lembradas (o mais antigo sai primeiro). */
const MAX_ENTRIES = 200;

/**
 * Caminho percorrido no app (ux.md §16): de onde o usuário veio (para "← Voltar para <origem>"), o último endereço de
 * cada lista (a trilha e o "← Lista" voltam com os filtros) e a rolagem de cada entrada do histórico, restaurada ao
 * voltar mesmo quando a lista carrega depois (o roteador só sabe restaurar a rolagem da janela, e o conteúdo do app
 * rola em `#conteudo`). Guarda só endereços do próprio app em memória, que nunca levam dado pessoal: as listas não
 * gravam na URL busca por pessoa (nome, documento, e-mail, telefone) — só ids e filtros de cadastro (ux.md §16).
 */
@Injectable({ providedIn: 'root' })
export class NavigationHistory {
  private readonly router = inject(Router);
  private readonly location = inject(Location);
  private readonly titles = inject(TitleStrategy);
  private readonly document = inject(DOCUMENT);
  private readonly auth = inject(AuthService);

  private readonly entries = new Map<number, HistoryEntry>();
  private readonly lastUrlByPath = new Map<string, string>();
  private readonly scroll = new Map<number, number>();
  private readonly currentKey = signal<number | null>(null);
  private readonly version = signal(0);

  /** Navegação em curso: chave de destino e se substitui a entrada atual. */
  private pending: { id: number; key: number; replace: boolean; popstate: boolean; urlState: boolean } | null = null;
  private cancelRestore: (() => void) | null = null;

  /** Entrada atual. */
  readonly current: Signal<HistoryEntry | null> = computed(() => {
    this.version();
    const key = this.currentKey();
    return key === null ? null : (this.entries.get(key) ?? null);
  });

  /** Entrada de onde o usuário chegou à tela atual (outra tela do app), ou nula. */
  readonly origin: Signal<HistoryEntry | null> = computed(() => {
    const current = this.current();
    const previous = current?.previous;
    const entry = previous === null || previous === undefined ? null : (this.entries.get(previous) ?? null);
    return entry && entry.path !== current!.path ? entry : null;
  });

  constructor() {
    // troca de usuário (logout, outro login): nada do caminho do anterior sobrevive
    let user = untracked(() => this.auth.user()?.id ?? null);
    effect(() => {
      const id = this.auth.user()?.id ?? null;
      if (id !== user) {
        untracked(() => this.clear());
      }
      user = id;
    });
    this.router.events.subscribe((event) => {
      if (event instanceof NavigationStart) {
        this.onStart(event);
      } else if (event instanceof NavigationEnd) {
        this.onEnd(event);
      } else if (event instanceof NavigationCancel || event instanceof NavigationError) {
        this.pending = null;
      }
    });
  }

  /** Último endereço visitado de uma tela (lista com os filtros), ou o próprio caminho. */
  lastUrl(path: string): string {
    this.version();
    return this.lastUrlByPath.get(pathOf(path)) ?? path;
  }

  /** Link para a lista (ou outra tela) com o último estado visto: use em `[routerLink]`. */
  linkTo(path: string): UrlTree {
    return this.router.parseUrl(this.lastUrl(path));
  }

  /** Troca o nome da tela atual (ex.: pelo nome do registro, quando a ficha carrega). */
  setCurrentLabel(label: string): void {
    const key = this.currentKey();
    const entry = key === null ? null : this.entries.get(key);
    if (entry && label && entry.label !== label) {
      this.entries.set(key!, { ...entry, label });
      this.version.update((v) => v + 1);
    }
  }

  /** Volta para a origem: o "voltar" do navegador (restaura a rolagem e o estado da URL de lá). */
  back(): void {
    const origin = this.origin();
    if (!origin) {
      return;
    }
    this.location.back();
  }

  /** Esquece o caminho, os endereços das listas e as rolagens (logout). */
  clear(): void {
    this.entries.clear();
    this.lastUrlByPath.clear();
    this.scroll.clear();
    this.currentKey.set(null);
    this.version.update((v) => v + 1);
  }

  /** Mantém só as entradas mais recentes (a navegação longa não cresce sem limite). */
  private prune(): void {
    for (const key of [...this.entries.keys()].sort((a, b) => a - b)) {
      if (this.entries.size <= MAX_ENTRIES) {
        break;
      }
      if (key !== this.currentKey()) {
        this.entries.delete(key);
        this.scroll.delete(key);
      }
    }
    while (this.lastUrlByPath.size > MAX_ENTRIES) {
      this.lastUrlByPath.delete(this.lastUrlByPath.keys().next().value!);
    }
  }

  private onStart(event: NavigationStart): void {
    const navigation = this.router.currentNavigation();
    const extras = navigation?.extras ?? {};
    const popstate = event.navigationTrigger === 'popstate';
    const key = popstate ? (event.restoredState?.navigationId ?? event.id) : event.id;
    // no "voltar" o roteador repõe o estado da entrada (que pode ter vindo de uma gravação de filtro): não conta
    const urlState = !popstate && !!extras.state?.['urlState'];
    this.pending = { id: event.id, key, replace: !!extras.replaceUrl || !!extras.skipLocationChange, popstate, urlState };
    // rolagem da tela que está sendo deixada (gravações de estado não mudam de tela)
    const current = this.currentKey();
    if (current !== null && !urlState) {
      this.scroll.set(current, this.scrollTop());
    }
    if (!urlState) {
      this.cancelRestore?.();
    }
  }

  private onEnd(event: NavigationEnd): void {
    const pending = this.pending?.id === event.id ? this.pending : null;
    this.pending = null;
    const url = event.urlAfterRedirects;
    const path = pathOf(url);
    const previousKey = this.currentKey();
    const previousEntry = previousKey === null ? null : this.entries.get(previousKey);
    let key = pending?.key ?? event.id;

    if (pending?.popstate && this.entries.has(key)) {
      // o roteador regrava `navigationId` da entrada com o id desta navegação: a chave acompanha
      const entry = this.entries.get(key)!;
      const restoredKey = key;
      key = this.historyKey(event.id);
      this.entries.delete(restoredKey);
      this.entries.set(key, { ...entry, key, url, path });
      const offset = this.scroll.get(restoredKey);
      if (offset !== undefined) {
        this.scroll.delete(restoredKey);
        this.scroll.set(key, offset);
      }
      for (const [k, e] of this.entries) {
        if (e.previous === restoredKey) {
          this.entries.set(k, { ...e, previous: key });
        }
      }
    } else if (pending?.replace && previousEntry) {
      // substitui a entrada atual: mantém de onde veio e, na mesma tela, o nome do registro
      this.entries.delete(previousKey!);
      const label = previousEntry.path === path ? previousEntry.label : this.labelOf();
      this.entries.set(key, { key, url, path, label, previous: previousEntry.previous });
      const offset = this.scroll.get(previousKey!);
      if (offset !== undefined) {
        this.scroll.set(key, offset);
      }
    } else {
      this.entries.set(key, { key, url, path, label: this.labelOf(), previous: pending?.popstate ? null : previousKey });
    }
    this.lastUrlByPath.delete(path); // reinsere no fim: a poda tira os caminhos menos recentes
    this.lastUrlByPath.set(path, url);
    this.currentKey.set(key);
    this.prune();
    this.version.update((v) => v + 1);

    if (pending?.urlState) {
      return; // filtro, página ou aba: a rolagem fica onde está
    }
    const saved = pending?.popstate ? this.scroll.get(key) : undefined;
    if (saved !== undefined && saved > 0) {
      this.restoreScroll(saved);
    } else {
      this.setScrollTop(0);
    }
  }

  /** Chave da entrada atual no histórico do navegador (o roteador grava `navigationId` no estado). */
  private historyKey(fallback: number): number {
    const id = (this.location.getState() as { navigationId?: unknown } | null)?.navigationId;
    return typeof id === 'number' ? id : fallback;
  }

  private labelOf(): string {
    return this.titles.buildTitle(this.router.routerState.snapshot) ?? '';
  }

  /** Rolador do conteúdo do app (`#conteudo`) ou, fora do shell, a própria página. */
  private scroller(): Element | null {
    const main = this.document.getElementById('conteudo');
    return main ?? this.document.scrollingElement;
  }

  private scrollTop(): number {
    return this.scroller()?.scrollTop ?? 0;
  }

  private setScrollTop(top: number): void {
    const el = this.scroller();
    if (el) {
      el.scrollTop = top;
    }
  }

  /**
   * Restaura a rolagem quando o conteúdo já tem altura para isso: as listas carregam depois da navegação. Desiste se o
   * usuário rolar, tocar ou usar o teclado antes, ou depois de alguns segundos (vai até onde der).
   */
  private restoreScroll(top: number): void {
    const view = this.document.defaultView;
    if (!view) {
      return;
    }
    const started = Date.now();
    let frame = 0;
    const stop = () => {
      view.cancelAnimationFrame(frame);
      for (const type of ['wheel', 'touchstart', 'keydown', 'pointerdown']) {
        view.removeEventListener(type, stop, true);
      }
      this.cancelRestore = null;
    };
    for (const type of ['wheel', 'touchstart', 'keydown', 'pointerdown']) {
      view.addEventListener(type, stop, { capture: true, passive: true });
    }
    const attempt = () => {
      const el = this.scroller();
      if (!el) {
        stop();
        return;
      }
      const max = el.scrollHeight - el.clientHeight;
      if (max >= top || Date.now() - started > RESTORE_TIMEOUT_MS) {
        el.scrollTop = Math.min(top, Math.max(max, 0));
        stop();
        return;
      }
      frame = view.requestAnimationFrame(attempt);
    };
    this.cancelRestore = stop;
    frame = view.requestAnimationFrame(attempt);
  }
}
