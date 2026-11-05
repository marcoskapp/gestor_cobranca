import { Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { AuthService } from '../auth/auth.service';
import { ApiError, toApiError, userMessage } from '../http/problem';
import { SupportChatApi } from './support-chat.api';
import { SupportChatStatus, SupportDownReason, SupportMessage, SupportRating, SupportTicket } from './support-chat.models';

export type ChatAvailability = 'unknown' | 'available' | 'unavailable' | 'error';

/**
 * Estado do chat de suporte na aba (sobrevive ao fechar e reabrir o painel): conversa atual,
 * mensagens, disponibilidade e o que está em andamento. A conversa só vive no servidor; aqui fica a
 * cópia para exibição. A conversa é de quem perguntou (os cartões trazem nome e documento mascarado de
 * devedores): no fim da sessão ou na troca de usuário o estado é descartado, e respostas que chegarem
 * depois disso são ignoradas.
 */
@Injectable({ providedIn: 'root' })
export class SupportChatStore {
  private readonly api = inject(SupportChatApi);
  private readonly auth = inject(AuthService);
  private userId: string | null = null;
  /** Muda a cada descarte do estado: resposta pedida numa sessão anterior não é aplicada. */
  private session = 0;

  private readonly statusState = signal<SupportChatStatus | null>(null);
  private readonly availabilityState = signal<ChatAvailability>('unknown');
  private readonly messagesState = signal<SupportMessage[]>([]);
  private readonly conversation = signal<string | null>(null);
  private readonly sendingState = signal(false);
  private readonly errorState = signal<string | null>(null);
  private readonly busyTickets = signal<ReadonlySet<string>>(new Set());
  private readonly ticketErrors = signal<Readonly<Record<string, string>>>({});

  readonly status = this.statusState.asReadonly();
  readonly availability = this.availabilityState.asReadonly();
  readonly messages = this.messagesState.asReadonly();
  readonly conversationId = this.conversation.asReadonly();
  readonly sending = this.sendingState.asReadonly();
  readonly error = this.errorState.asReadonly();
  readonly ticketsAvailable = computed(() => this.statusState()?.ticketsAvailable ?? false);
  readonly empty = computed(() => this.messagesState().length === 0);

  constructor() {
    // quem abriu o chat é o dono da conversa; o efeito descarta o estado quando isso muda (inclusive para ninguém)
    this.userId = untracked(() => this.auth.user()?.id ?? null);
    effect(() => {
      const id = this.auth.user()?.id ?? null;
      untracked(() => {
        if (id !== this.userId) {
          this.userId = id;
          this.reset();
        }
      });
    });
  }

  async loadStatus(): Promise<void> {
    const session = this.session;
    try {
      const status = await firstValueFrom(this.api.status());
      if (session !== this.session) {
        return;
      }
      this.statusState.set(status);
      this.availabilityState.set(status.available ? 'available' : 'unavailable');
    } catch {
      if (session === this.session) {
        this.availabilityState.set('error');
      }
    }
  }

  /** @returns a resposta, ou `null` se a pergunta não foi aceita (o erro fica em `error`) */
  async send(text: string, screen: string | null): Promise<SupportMessage | null> {
    const question = text.trim();
    if (!question || this.sendingState()) {
      return null;
    }
    const session = this.session;
    this.sendingState.set(true);
    this.errorState.set(null);
    try {
      const result = await firstValueFrom(this.api.ask(this.conversation(), screen, question));
      if (session !== this.session) {
        return null; // a sessão acabou ou mudou de usuário enquanto a resposta chegava
      }
      this.conversation.set(result.conversationId);
      this.messagesState.update((list) => [...list, result.question, result.answer]);
      return result.answer;
    } catch (e) {
      if (session !== this.session) {
        return null;
      }
      const error = toApiError(e);
      if (error.status === 503 && error.code === 'helpdesk.unavailable') {
        this.availabilityState.set('unavailable');
      }
      if (error.status === 404 && this.conversation()) {
        // conversa removida ou de outra sessão: recomeça
        this.conversation.set(null);
      }
      this.errorState.set(chatMessage(error));
      return null;
    } finally {
      if (session === this.session) {
        this.sendingState.set(false);
      }
    }
  }

  newConversation(): void {
    this.conversation.set(null);
    this.messagesState.set([]);
    this.errorState.set(null);
  }

  async rate(message: SupportMessage, rating: SupportRating, reason: SupportDownReason | null = null): Promise<void> {
    const session = this.session;
    try {
      const updated = await firstValueFrom(this.api.feedback(message.id, rating, reason));
      if (session === this.session) {
        this.replace(updated.id, (m) => ({ ...m, feedback: updated.feedback }));
      }
    } catch (e) {
      if (session === this.session) {
        this.errorState.set(chatMessage(toApiError(e)));
      }
    }
  }

  ticketBusy(id: string): boolean {
    return this.busyTickets().has(id);
  }

  ticketError(id: string): string | null {
    return this.ticketErrors()[id] ?? null;
  }

  async confirmTicket(ticket: SupportTicket): Promise<SupportTicket | null> {
    return this.ticketCall(ticket, () => firstValueFrom(this.api.confirmTicket(ticket.id)));
  }

  async cancelTicket(ticket: SupportTicket): Promise<SupportTicket | null> {
    return this.ticketCall(ticket, () => firstValueFrom(this.api.cancelTicket(ticket.id)));
  }

  private async ticketCall(ticket: SupportTicket, call: () => Promise<SupportTicket>): Promise<SupportTicket | null> {
    if (this.busyTickets().has(ticket.id)) {
      return null;
    }
    const session = this.session;
    this.busyTickets.update((s) => new Set([...s, ticket.id]));
    this.ticketErrors.update((errors) => {
      const next = { ...errors };
      delete next[ticket.id];
      return next;
    });
    try {
      const updated = await call();
      if (session !== this.session) {
        return null;
      }
      this.messagesState.update((list) => list.map((m) => (m.ticket?.id === updated.id ? { ...m, ticket: updated } : m)));
      return updated;
    } catch (e) {
      if (session !== this.session) {
        return null;
      }
      this.ticketErrors.update((errors) => ({ ...errors, [ticket.id]: chatMessage(toApiError(e)) }));
      return null;
    } finally {
      if (session === this.session) {
        this.busyTickets.update((s) => new Set([...s].filter((id) => id !== ticket.id)));
      }
    }
  }

  /** Descarta tudo o que é da sessão anterior (conversa, mensagens, situação e erros). */
  private reset(): void {
    this.session++;
    this.statusState.set(null);
    this.availabilityState.set('unknown');
    this.messagesState.set([]);
    this.conversation.set(null);
    this.sendingState.set(false);
    this.errorState.set(null);
    this.busyTickets.set(new Set());
    this.ticketErrors.set({});
  }

  private replace(id: string, change: (m: SupportMessage) => SupportMessage): void {
    this.messagesState.update((list) => list.map((m) => (m.id === id ? change(m) : m)));
  }
}

/** Limites (429) e indisponibilidade (503) trazem a orientação do servidor; o resto segue o padrão. */
function chatMessage(error: ApiError): string {
  if ((error.status === 429 || error.status === 503) && error.detail) {
    return error.detail;
  }
  return userMessage(error);
}
