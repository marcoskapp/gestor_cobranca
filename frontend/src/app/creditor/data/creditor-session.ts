import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Observable, catchError, finalize, firstValueFrom, map, of, shareReplay } from 'rxjs';
import { CrossTabCoordinator } from '../../core/auth/cross-tab';
import { CreditorApi } from './creditor.api';
import { CreditorLoginResponse, CreditorMe } from './creditor.models';

/** Resultado da renovação: renovada, encerrada no servidor ou servidor indisponível (rede, 5xx, 429). */
export type RestoreOutcome = 'renewed' | 'ended' | 'unavailable';

/**
 * Trava própria entre abas: o refresh do credor é rotativo com detecção de reuso — duas abas renovando juntas seriam
 * lidas como roubo (sessão encerrada e alerta falso). Com a trava, a segunda aba renova depois, já com o cookie novo.
 */
const CREDITOR_REFRESH_LOCK = 'gc-creditor-refresh';

interface State {
  token: string;
  expiresAt: number;
  me: CreditorMe;
}

/**
 * Sessão da pessoa do credor, separada da sessão interna e da do devedor (outro token, outra audiência — ADR 0011).
 * O token fica só em memória; recarregar a página renova pelo cookie `HttpOnly` do portal do credor.
 */
@Injectable({ providedIn: 'root' })
export class CreditorSession {
  private readonly api = inject(CreditorApi);
  private readonly crossTab = inject(CrossTabCoordinator);
  private readonly state = signal<State | null>(null);
  private restoring: Observable<RestoreOutcome> | null = null;

  readonly token = computed(() => this.state()?.token ?? null);
  readonly me = computed(() => this.state()?.me ?? null);
  /** Motivo do último encerramento (aviso na tela de entrada). */
  readonly endedReason = signal<'expired' | 'logout' | null>(null);

  isActiveFor(slug: string): boolean {
    const s = this.state();
    return !!s && s.me.tenantSlug === slug;
  }

  has(permission: string): boolean {
    return this.me()?.permissions.includes(permission) ?? false;
  }

  start(result: CreditorLoginResponse): void {
    if (result.status !== 'AUTHENTICATED' || !result.accessToken || !result.me) {
      return;
    }
    this.state.set({
      token: result.accessToken,
      expiresAt: result.accessTokenExpiresAt ? Date.parse(result.accessTokenExpiresAt) : Date.now() + 5 * 60_000,
      me: result.me,
    });
    this.endedReason.set(null);
  }

  clear(reason: 'expired' | 'logout' | null = null): void {
    this.state.set(null);
    this.endedReason.set(reason);
  }

  /** O token vence em menos de um minuto (renovar antes de chamar a API). */
  expiringSoon(now = Date.now()): boolean {
    const s = this.state();
    return !!s && s.expiresAt - now < 60_000;
  }

  /** Renova pelo cookie: uma vez por aba e com exclusão mútua entre abas. */
  restore(): Observable<RestoreOutcome> {
    if (!this.restoring) {
      this.restoring = this.crossTab
        .withRefreshLock(() => firstValueFrom(this.api.refresh()), CREDITOR_REFRESH_LOCK)
        .pipe(
          map((result): RestoreOutcome => {
            if (result.status === 'AUTHENTICATED' && result.accessToken && result.me) {
              this.start(result);
              return 'renewed';
            }
            return 'ended';
          }),
          catchError((error: unknown) =>
            of<RestoreOutcome>(
              error instanceof HttpErrorResponse && (error.status === 401 || error.status === 403) ? 'ended' : 'unavailable',
            ),
          ),
          finalize(() => (this.restoring = null)),
          shareReplay({ bufferSize: 1, refCount: false }),
        );
    }
    return this.restoring;
  }
}
