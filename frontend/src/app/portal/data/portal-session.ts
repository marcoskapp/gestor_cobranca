import { Injectable, computed, signal } from '@angular/core';
import { SessionGrant } from './portal.models';

interface StoredSession {
  slug: string;
  token: string;
  expiresAt: string;
}

/**
 * Sessão do devedor no portal, separada da sessão dos usuários internos (outro token, outra
 * audiência). O token fica só em memória (regra do projeto: nenhum token em armazenamento do
 * navegador); recarregar a página exige entrar de novo — a sessão é curta por desenho.
 */
@Injectable({ providedIn: 'root' })
export class PortalSession {
  private readonly state = signal<StoredSession | null>(null);

  readonly token = computed(() => this.state()?.token ?? null);
  readonly slug = computed(() => this.state()?.slug ?? null);
  readonly expiresAt = computed(() => {
    const value = this.state()?.expiresAt;
    return value ? new Date(value) : null;
  });
  /** Motivo do último encerramento (exibido na tela de entrada). */
  readonly endedReason = signal<'expired' | 'logout' | null>(null);

  /** Sessão do portal informado (restaura da aba, se houver). */
  activeFor(slug: string): boolean {
    const s = this.state();
    return !!s && s.slug === slug && new Date(s.expiresAt).getTime() > Date.now();
  }

  start(slug: string, grant: SessionGrant): void {
    const session = { slug, token: grant.accessToken, expiresAt: grant.expiresAt };
    this.state.set(session);
    this.endedReason.set(null);
  }

  renew(grant: SessionGrant): void {
    const current = this.state();
    if (current) {
      this.start(current.slug, grant);
    }
  }

  clear(reason: 'expired' | 'logout' | null = null): void {
    this.state.set(null);
    this.endedReason.set(reason);
  }

  /** O token vence em menos de um minuto (renovar antes de chamar a API). */
  expiringSoon(now = Date.now()): boolean {
    const expires = this.expiresAt();
    return !!expires && expires.getTime() - now < 60_000;
  }
}
