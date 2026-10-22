import { Injectable, OnDestroy } from '@angular/core';
import { Observable, Subject, defer } from 'rxjs';

const LOCK_NAME = 'gc-auth-refresh';
const CHANNEL_NAME = 'gc-auth';

/** Mensagens entre abas. Nunca transportam tokens ou dados pessoais. */
export interface CrossTabMessage {
  type: 'logout';
}

/**
 * Coordenação entre abas do mesmo navegador.
 *
 * O refresh token é rotativo com detecção de reuso: se duas abas enviarem o mesmo cookie ao mesmo
 * tempo, o servidor revoga a sessão. A Web Locks API serializa as renovações entre abas; cada uma
 * usa o cookie já rotacionado pela anterior.
 */
@Injectable({ providedIn: 'root' })
export class CrossTabCoordinator implements OnDestroy {
  private readonly channel: BroadcastChannel | null =
    typeof BroadcastChannel === 'undefined' ? null : new BroadcastChannel(CHANNEL_NAME);
  private readonly incoming = new Subject<CrossTabMessage>();

  readonly messages: Observable<CrossTabMessage> = this.incoming.asObservable();

  constructor() {
    this.channel?.addEventListener('message', (event: MessageEvent<unknown>) => {
      if (isMessage(event.data)) {
        this.incoming.next(event.data);
      }
    });
  }

  /** Executa a operação com exclusão mútua entre abas (quando o navegador suporta). */
  withRefreshLock<T>(operation: () => Promise<T>): Observable<T> {
    return defer(() => {
      const locks = typeof navigator === 'undefined' ? undefined : navigator.locks;
      return locks ? locks.request(LOCK_NAME, { mode: 'exclusive' }, () => operation()) : operation();
    });
  }

  broadcast(message: CrossTabMessage): void {
    this.channel?.postMessage(message);
  }

  ngOnDestroy(): void {
    this.channel?.close();
    this.incoming.complete();
  }
}

function isMessage(value: unknown): value is CrossTabMessage {
  return typeof value === 'object' && value !== null && (value as CrossTabMessage).type === 'logout';
}
