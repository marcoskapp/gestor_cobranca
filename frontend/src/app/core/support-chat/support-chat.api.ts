import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../config/api';
import { silentErrorContext } from '../http/http-context';
import {
  SupportAnswer,
  SupportChatStatus,
  SupportConversation,
  SupportDownReason,
  SupportMessage,
  SupportRating,
  SupportTicket,
} from './support-chat.models';

const BASE = `${API_V1}/helpdesk`;

/**
 * API do chat de suporte. Erros (limites, indisponível) são mostrados dentro do painel, sem
 * notificação global (contexto silencioso).
 */
@Injectable({ providedIn: 'root' })
export class SupportChatApi {
  private readonly http = inject(HttpClient);

  status(): Observable<SupportChatStatus> {
    return this.http.get<SupportChatStatus>(`${BASE}/status`, { context: silentErrorContext() });
  }

  ask(conversationId: string | null, screen: string | null, text: string): Observable<SupportAnswer> {
    return this.http.post<SupportAnswer>(`${BASE}/messages`, { conversationId, screen, text }, { context: silentErrorContext() });
  }

  conversation(id: string): Observable<SupportConversation> {
    return this.http.get<SupportConversation>(`${BASE}/conversations/${encodeURIComponent(id)}`, { context: silentErrorContext() });
  }

  feedback(messageId: string, rating: SupportRating, reason: SupportDownReason | null): Observable<SupportMessage> {
    return this.http.post<SupportMessage>(`${BASE}/messages/${encodeURIComponent(messageId)}/feedback`, { rating, reason }, {
      context: silentErrorContext(),
    });
  }

  confirmTicket(id: string): Observable<SupportTicket> {
    return this.http.post<SupportTicket>(`${BASE}/tickets/${encodeURIComponent(id)}/confirm`, null, { context: silentErrorContext() });
  }

  cancelTicket(id: string): Observable<SupportTicket> {
    return this.http.post<SupportTicket>(`${BASE}/tickets/${encodeURIComponent(id)}/cancel`, null, { context: silentErrorContext() });
  }
}
