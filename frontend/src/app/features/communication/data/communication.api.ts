import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { toHttpParams } from '../../../core/http/http-params';
import { PageRequest, PageResponse } from '../../../shared/models/page';
import {
  AccountView,
  ApprovalRequest,
  ConversationDetail,
  ConversationView,
  CreateAccountRequest,
  CreateTemplateRequest,
  DraftView,
  HandoffView,
  InboxCounts,
  InboxFilter,
  MessageView,
  ResponseTargetView,
  SendMessageRequest,
  TemplateContent,
  TemplateFilter,
  TemplatePreview,
  TemplateView,
  UpdateAccountRequest,
} from './communication.models';

const BASE = `${API_V1}/communication`;

/** Caixa de entrada (F12): conversas, dono do atendimento, transferências e rascunhos do assistente. */
@Injectable({ providedIn: 'root' })
export class ConversationsApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${BASE}/conversations`;

  inbox(filter: InboxFilter, page: PageRequest): Observable<PageResponse<ConversationView>> {
    return this.http.get<PageResponse<ConversationView>>(this.base, { params: toHttpParams({ ...filter, page: page.page, size: page.size }) });
  }

  /** Contagem por aba (e estouros de SLA) numa única chamada. */
  counts(channel: string | null, awaitingReply: boolean | null): Observable<InboxCounts> {
    return this.http.get<InboxCounts>(`${this.base}/counts`, { params: toHttpParams({ channel, awaitingReply }) });
  }

  /** Linha do tempo do devedor: mensagens de todas as conversas e canais, mais recentes primeiro. */
  debtorMessages(debtorId: string, creditorId: string | null, page: PageRequest): Observable<PageResponse<MessageView>> {
    return this.http.get<PageResponse<MessageView>>(`${BASE}/debtors/${debtorId}/messages`, {
      params: toHttpParams({ creditorId, page: page.page, size: page.size }),
    });
  }

  /** Metas de primeira resposta vigentes por canal. */
  responseTargets(): Observable<ResponseTargetView[]> {
    return this.http.get<ResponseTargetView[]>(`${BASE}/response-targets`);
  }

  get(id: string): Observable<ConversationDetail> {
    return this.http.get<ConversationDetail>(`${this.base}/${id}`);
  }

  messages(id: string, page: PageRequest): Observable<PageResponse<MessageView>> {
    return this.http.get<PageResponse<MessageView>>(`${this.base}/${id}/messages`, { params: toHttpParams({ page: page.page, size: page.size }) });
  }

  handoffs(id: string): Observable<HandoffView[]> {
    return this.http.get<HandoffView[]>(`${this.base}/${id}/handoffs`);
  }

  markRead(id: string): Observable<ConversationView> {
    return this.http.post<ConversationView>(`${this.base}/${id}/read`, null);
  }

  identify(id: string, debtorId: string, creditorId: string | null): Observable<ConversationView> {
    return this.http.post<ConversationView>(`${this.base}/${id}/identify`, { debtorId, creditorId });
  }

  takeOver(id: string, reason: string | null): Observable<ConversationView> {
    return this.http.post<ConversationView>(`${this.base}/${id}/take-over`, { reason });
  }

  release(id: string, reason: string | null): Observable<ConversationView> {
    return this.http.post<ConversationView>(`${this.base}/${id}/release`, { reason });
  }

  assign(id: string, userId: string, reason: string | null): Observable<ConversationView> {
    return this.http.post<ConversationView>(`${this.base}/${id}/assign`, { userId, reason });
  }

  returnToAssistant(id: string, reason: string | null): Observable<ConversationView> {
    return this.http.post<ConversationView>(`${this.base}/${id}/assistant`, { reason });
  }

  close(id: string, reason: string | null): Observable<ConversationView> {
    return this.http.post<ConversationView>(`${this.base}/${id}/close`, { reason });
  }

  /** Envia o rascunho do assistente; com `content`, a edição do operador fica registrada. */
  sendDraft(id: string, draftId: string, content: string | null): Observable<MessageView> {
    return this.http.post<MessageView>(`${this.base}/${id}/drafts/${draftId}/send`, { content });
  }

  discardDraft(id: string, draftId: string): Observable<DraftView> {
    return this.http.post<DraftView>(`${this.base}/${id}/drafts/${draftId}/discard`, null);
  }
}

/** Envio pelo operador (201 `QUEUED` ou `FAILED/CONTACT_DENIED` quando o porteiro já nega). */
@Injectable({ providedIn: 'root' })
export class MessagesApi {
  private readonly http = inject(HttpClient);

  /** `idempotencyKey` permite repetir o envio com segurança após falha de rede. */
  send(request: SendMessageRequest, idempotencyKey: string): Observable<MessageView> {
    return this.http.post<MessageView>(
      `${BASE}/messages`,
      { ...request, debtorId: null, creditorId: null, caseId: null, channel: null, contactId: null },
      { headers: new HttpHeaders({ 'Idempotency-Key': idempotencyKey }) },
    );
  }
}

/** Contas de canal: credenciais só entram (criação e rotação), nunca são devolvidas. */
@Injectable({ providedIn: 'root' })
export class ChannelsApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${BASE}/channels`;

  list(): Observable<AccountView[]> {
    return this.http.get<AccountView[]>(this.base);
  }

  create(request: CreateAccountRequest): Observable<AccountView> {
    return this.http.post<AccountView>(this.base, request);
  }

  update(id: string, request: UpdateAccountRequest): Observable<AccountView> {
    return this.http.put<AccountView>(`${this.base}/${id}`, request);
  }

  rotateCredentials(id: string, credentials: Record<string, string>): Observable<AccountView> {
    return this.http.put<AccountView>(`${this.base}/${id}/credentials`, { credentials });
  }

  activate(id: string): Observable<AccountView> {
    return this.http.post<AccountView>(`${this.base}/${id}/activate`, null);
  }

  deactivate(id: string): Observable<AccountView> {
    return this.http.post<AccountView>(`${this.base}/${id}/deactivate`, null);
  }
}

/** Templates versionados por credor (ou da assessoria) e canal. */
@Injectable({ providedIn: 'root' })
export class TemplatesApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${BASE}/templates`;

  search(filter: TemplateFilter, page: PageRequest): Observable<PageResponse<TemplateView>> {
    return this.http.get<PageResponse<TemplateView>>(this.base, { params: toHttpParams({ ...filter, page: page.page, size: page.size }) });
  }

  get(id: string): Observable<TemplateView> {
    return this.http.get<TemplateView>(`${this.base}/${id}`);
  }

  create(request: CreateTemplateRequest): Observable<TemplateView> {
    return this.http.post<TemplateView>(this.base, request);
  }

  update(id: string, content: TemplateContent): Observable<TemplateView> {
    return this.http.put<TemplateView>(`${this.base}/${id}`, content);
  }

  approval(id: string, request: ApprovalRequest): Observable<TemplateView> {
    return this.http.post<TemplateView>(`${this.base}/${id}/approval`, request);
  }

  publish(id: string): Observable<TemplateView> {
    return this.http.post<TemplateView>(`${this.base}/${id}/publish`, null);
  }

  retire(id: string): Observable<TemplateView> {
    return this.http.post<TemplateView>(`${this.base}/${id}/retire`, null);
  }

  preview(id: string, values: Record<string, string>): Observable<TemplatePreview> {
    return this.http.post<TemplatePreview>(`${this.base}/${id}/preview`, values);
  }
}
