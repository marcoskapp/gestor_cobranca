import { LiveAnnouncer } from '@angular/cdk/a11y';
import { DOCUMENT, DestroyRef, Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { Subscription } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { PendingWorkApi } from './pending-work.api';
import { changeAnnouncement } from './pending-work.format';
import { PendingWorkSummary } from './pending-work.models';

/** Intervalo da atualização periódica (pausada com a aba oculta). */
export const PENDING_WORK_POLL_MS = 60_000;
/** Ao abrir o resumo ou a tela inicial, dados mais antigos que isto são recarregados. */
export const PENDING_WORK_STALE_MS = 15_000;

export type PendingWorkStatus = 'idle' | 'loading' | 'ready' | 'error';

/**
 * Estado único de "Minhas pendências" na aba: o indicador da barra superior, a tela inicial e a lista
 * completa leem a mesma consulta. A atualização é periódica (60 s) enquanto houver alguém conectado e
 * a aba estiver visível; ao voltar à aba, recarrega se os dados estiverem velhos. Mudança nas
 * contagens é anunciada de forma polida (uma vez por mudança, nunca na primeira carga). Ao sair da
 * sessão, o estado é descartado.
 */
@Injectable({ providedIn: 'root' })
export class PendingWorkStore {
  private readonly api = inject(PendingWorkApi);
  private readonly auth = inject(AuthService);
  private readonly announcer = inject(LiveAnnouncer);
  private readonly document = inject(DOCUMENT);

  private readonly current = signal<PendingWorkSummary | null>(null);
  private readonly state = signal<PendingWorkStatus>('idle');
  private consumers = 0;
  private timer: ReturnType<typeof setInterval> | null = null;
  private request: Subscription | null = null;
  private loadedAt = 0;

  readonly summary = this.current.asReadonly();
  readonly status = this.state.asReadonly();
  readonly total = computed(() => this.current()?.total ?? 0);
  readonly overdue = computed(() => this.current()?.overdue ?? 0);

  constructor() {
    const onVisibility = () => {
      if (this.document.visibilityState === 'visible' && this.consumers > 0 && this.isStale(PENDING_WORK_POLL_MS)) {
        this.refresh();
      }
    };
    this.document.addEventListener('visibilitychange', onVisibility);
    effect(() => {
      const user = this.auth.user();
      untracked(() => {
        if (!user) {
          this.reset();
        }
      });
    });
    inject(DestroyRef).onDestroy(() => {
      this.document.removeEventListener('visibilitychange', onVisibility);
      this.stopTimer();
      this.request?.unsubscribe();
    });
  }

  /**
   * Registra um consumidor (indicador, tela inicial, lista): carrega se preciso e mantém a atualização
   * periódica enquanto houver consumidores. Devolve a função que desfaz o registro.
   */
  connect(): () => void {
    this.consumers++;
    if (this.state() === 'idle' || this.isStale(PENDING_WORK_STALE_MS)) {
      this.refresh();
    }
    if (!this.timer) {
      this.timer = setInterval(() => {
        if (this.document.visibilityState !== 'hidden') {
          this.refresh();
        }
      }, PENDING_WORK_POLL_MS);
    }
    let connected = true;
    return () => {
      if (!connected) {
        return;
      }
      connected = false;
      this.consumers = Math.max(0, this.consumers - 1);
      if (this.consumers === 0) {
        this.stopTimer();
      }
    };
  }

  /** Recarrega se os dados tiverem mais que {@link PENDING_WORK_STALE_MS}. */
  refreshIfStale(): void {
    if (this.isStale(PENDING_WORK_STALE_MS)) {
      this.refresh();
    }
  }

  refresh(): void {
    if (!this.auth.user() || this.request) {
      return;
    }
    if (!this.current()) {
      this.state.set('loading');
    }
    this.request = this.api.mine().subscribe({
      next: (next) => {
        const previous = this.current();
        this.current.set(next);
        this.state.set('ready');
        this.loadedAt = Date.now();
        const message = changeAnnouncement(previous, next);
        if (message) {
          void this.announcer.announce(message, 'polite');
        }
      },
      error: () => {
        this.request = null;
        // mantém a última resposta boa; sem ela, a interface mostra o erro
        this.state.set(this.current() ? 'ready' : 'error');
      },
      complete: () => {
        this.request = null;
      },
    });
  }

  private isStale(maxAgeMs: number): boolean {
    return this.loadedAt === 0 || Date.now() - this.loadedAt >= maxAgeMs;
  }

  private stopTimer(): void {
    if (this.timer) {
      clearInterval(this.timer);
      this.timer = null;
    }
  }

  private reset(): void {
    this.request?.unsubscribe();
    this.request = null;
    this.current.set(null);
    this.state.set('idle');
    this.loadedAt = 0;
  }
}
