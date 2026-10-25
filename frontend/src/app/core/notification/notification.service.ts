import { Injectable, signal } from '@angular/core';

export type NotificationTone = 'success' | 'info' | 'error';

export interface AppNotification {
  id: number;
  tone: NotificationTone;
  message: string;
  /** Tempo em tela (ms); pausado enquanto o ponteiro ou o foco estão sobre a notificação. */
  delay: number;
}

const DELAY: Record<NotificationTone, number> = { success: 5000, info: 5000, error: 10000 };
const MAX_VISIBLE = 4;

/**
 * Mensagens transitórias ao usuário (toasts do ng-bootstrap, exibidos por `app-toasts` na raiz
 * da aplicação), anunciadas a leitores de tela: `polite` para sucesso/informação e `assertive`
 * para erro. Mesma API do serviço anterior (MatSnackBar).
 */
@Injectable({ providedIn: 'root' })
export class NotificationService {
  private nextId = 0;
  private readonly list = signal<readonly AppNotification[]>([]);
  readonly notifications = this.list.asReadonly();

  success(message: string): void {
    this.push('success', message);
  }

  info(message: string): void {
    this.push('info', message);
  }

  error(message: string): void {
    this.push('error', message);
  }

  dismiss(id: number): void {
    this.list.update((items) => items.filter((n) => n.id !== id));
  }

  private push(tone: NotificationTone, message: string): void {
    const item: AppNotification = { id: ++this.nextId, tone, message, delay: DELAY[tone] };
    // a mesma mensagem não se empilha (ex.: vários erros de rede seguidos)
    this.list.update((items) => [...items.filter((n) => n.message !== message), item].slice(-MAX_VISIBLE));
  }
}
