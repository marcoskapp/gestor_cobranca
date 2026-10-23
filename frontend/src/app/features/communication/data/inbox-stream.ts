import { Injectable, inject, signal } from '@angular/core';
import { Observable, Subject, share } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { API_V1 } from '../../../core/config/api';

/** Aviso do servidor: uma conversa mudou, ou recarregar tudo (avisos podem ter se perdido). */
export type InboxEvent = { kind: 'conversation'; conversationId: string } | { kind: 'resync' };

export const INBOX_STREAM_URL = `${API_V1}/communication/inbox/stream`;

/** Espera entre tentativas de reconexão (cresce até o máximo; zera ao conectar). */
export const STREAM_RETRY_MS = { initial: 2_000, max: 120_000 } as const;

interface SseMessage {
  event: string;
  data: string;
}

/**
 * Separa um buffer de texto SSE em mensagens completas (terminadas por linha em branco) e devolve
 * o restante incompleto. Comentários (`:ping`) e campos desconhecidos são ignorados.
 */
export function parseSse(buffer: string): { messages: SseMessage[]; rest: string } {
  const normalized = buffer.replace(/\r\n?/g, '\n');
  const blocks = normalized.split('\n\n');
  const rest = blocks.pop() ?? '';
  const messages: SseMessage[] = [];
  for (const block of blocks) {
    let event = 'message';
    const data: string[] = [];
    for (const line of block.split('\n')) {
      if (!line || line.startsWith(':')) {
        continue;
      }
      const sep = line.indexOf(':');
      const field = sep < 0 ? line : line.slice(0, sep);
      const value = sep < 0 ? '' : line.slice(sep + 1).replace(/^ /, '');
      if (field === 'event') {
        event = value;
      } else if (field === 'data') {
        data.push(value);
      }
    }
    if (data.length || event !== 'message') {
      messages.push({ event, data: data.join('\n') });
    }
  }
  return { messages, rest };
}

/** Converte a mensagem SSE em aviso da caixa de entrada (nulo para eventos de controle). */
export function toInboxEvent(m: SseMessage): InboxEvent | null {
  if (m.event === 'resync') {
    return { kind: 'resync' };
  }
  if (m.event === 'conversation') {
    try {
      const id = (JSON.parse(m.data) as { conversationId?: unknown }).conversationId;
      return typeof id === 'string' ? { kind: 'conversation', conversationId: id } : null;
    } catch {
      return null;
    }
  }
  return null;
}

/**
 * Tempo real da caixa de entrada por Server-Sent Events (`GET /communication/inbox/stream`).
 * Usa `fetch` com o cabeçalho `Authorization` (o `EventSource` não envia cabeçalhos e exigiria o
 * token na URL). O servidor encerra cada conexão em até 5 min ou ao fim da validade do token; o
 * cliente reconecta com o token vigente. Sem token (sessão ociosa) ou com o servidor recusando,
 * `live` fica falso e as telas seguem com a atualização periódica — que continua, mais espaçada,
 * mesmo com a conexão ativa, como rede de segurança.
 */
@Injectable({ providedIn: 'root' })
export class InboxStream {
  private readonly auth = inject(AuthService);

  /** Conexão aberta e recebendo avisos. */
  readonly live = signal(false);

  private readonly shared: Observable<InboxEvent> = new Observable<InboxEvent>((subscriber) => {
    const controller = new AbortController();
    const subject = new Subject<InboxEvent>();
    const sub = subject.subscribe(subscriber);
    void this.run(controller.signal, subject);
    return () => {
      controller.abort();
      sub.unsubscribe();
      this.live.set(false);
    };
  }).pipe(share());

  /** Avisos enquanto houver assinantes (uma única conexão compartilhada). */
  events(): Observable<InboxEvent> {
    return this.shared;
  }

  private async run(signal: AbortSignal, out: Subject<InboxEvent>): Promise<void> {
    let delay: number = STREAM_RETRY_MS.initial;
    while (!signal.aborted) {
      const token = this.auth.accessToken();
      if (token && !this.auth.isTokenExpiring()) {
        const outcome = await this.connect(token, signal, out);
        if (outcome === 'connected') {
          delay = STREAM_RETRY_MS.initial;
        }
      }
      this.live.set(false);
      if (signal.aborted) {
        return;
      }
      await wait(delay, signal);
      delay = Math.min(delay * 2, STREAM_RETRY_MS.max);
    }
  }

  /** @returns `connected` se a conexão chegou a abrir (mesmo que depois encerrada) */
  private async connect(token: string, signal: AbortSignal, out: Subject<InboxEvent>): Promise<'connected' | 'failed'> {
    let response: Response;
    try {
      response = await fetch(INBOX_STREAM_URL, {
        headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
        credentials: 'same-origin',
        cache: 'no-store',
        signal,
      });
    } catch {
      return 'failed';
    }
    if (!response.ok || !response.body) {
      return 'failed';
    }
    this.live.set(true);
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    try {
      for (;;) {
        const { value, done } = await reader.read();
        if (done) {
          break;
        }
        buffer += decoder.decode(value, { stream: true });
        const parsed = parseSse(buffer);
        buffer = parsed.rest;
        for (const m of parsed.messages) {
          const event = toInboxEvent(m);
          if (event) {
            out.next(event);
          }
        }
      }
    } catch {
      // conexão interrompida (rede, desligamento da instância, aba em segundo plano): reconecta
    }
    return 'connected';
  }
}

function wait(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const timer = setTimeout(resolve, ms);
    signal.addEventListener('abort', () => {
      clearTimeout(timer);
      resolve();
    }, { once: true });
  });
}

