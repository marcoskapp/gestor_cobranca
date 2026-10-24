import { Injectable, computed, signal } from '@angular/core';
import { SessionGrant } from './portal.models';

const STORAGE_KEY = 'portal.session';

interface StoredSession {
  slug: string;
  token: string;
  expiresAt: string;
}

/**
 * Sessão do devedor no portal, separada da sessão dos usuários internos (outro token, outra
 * audiência, outro armazenamento). O token fica em memória e no `sessionStorage` da aba (some ao
 * fechar a aba; recarregar a página não derruba a sessão curta). Nunca em `localStorage` ou cookie.
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
    if (!this.state()) {
      this.restore();
    }
    const s = this.state();
    return !!s && s.slug === slug && new Date(s.expiresAt).getTime() > Date.now();
  }

  start(slug: string, grant: SessionGrant): void {
    const session = { slug, token: grant.accessToken, expiresAt: grant.expiresAt };
    this.state.set(session);
    this.endedReason.set(null);
    write(session);
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
    write(null);
  }

  /** O token vence em menos de um minuto (renovar antes de chamar a API). */
  expiringSoon(now = Date.now()): boolean {
    const expires = this.expiresAt();
    return !!expires && expires.getTime() - now < 60_000;
  }

  private restore(): void {
    try {
      const raw = sessionStorage.getItem(STORAGE_KEY);
      const parsed = raw ? (JSON.parse(raw) as StoredSession) : null;
      if (parsed?.token && parsed.slug && parsed.expiresAt) {
        this.state.set(parsed);
      }
    } catch {
      // armazenamento indisponível (modo privado, bloqueado): sessão só em memória
    }
  }
}

function write(session: StoredSession | null): void {
  try {
    if (session) {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(session));
    } else {
      sessionStorage.removeItem(STORAGE_KEY);
    }
  } catch {
    // sem armazenamento: a sessão continua em memória
  }
}
