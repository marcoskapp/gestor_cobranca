import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { silentErrorContext } from '../../../core/http/http-context';
import { toHttpParams } from '../../../core/http/http-params';
import { PageRequest, PageResponse } from '../../../shared/models/page';
import { CreatePolicyRequest, PolicySettings, PolicyView, PromptView, QualityView, ReviewRequest, ReviewView, TurnDetail, TurnView } from './assistant.models';

const BASE = `${API_V1}/assistant`;

/** Políticas do assistente (versões, publicação, desligamento por carteira) e prompts. */
@Injectable({ providedIn: 'root' })
export class AssistantPoliciesApi {
  private readonly http = inject(HttpClient);

  list(): Observable<PolicyView[]> {
    return this.http.get<PolicyView[]>(`${BASE}/policies`);
  }

  get(id: string): Observable<PolicyView> {
    return this.http.get<PolicyView>(`${BASE}/policies/${id}`);
  }

  versions(id: string): Observable<PolicyView[]> {
    return this.http.get<PolicyView[]>(`${BASE}/policies/${id}/versions`);
  }

  /** Política vigente para o credor/carteira; 204 (nulo) = o assistente não atua. */
  effective(creditorId: string, portfolioId: string | null): Observable<PolicyView | null> {
    return this.http.get<PolicyView | null>(`${BASE}/policies/effective`, { params: toHttpParams({ creditorId, portfolioId }) });
  }

  template(mode: string | null): Observable<PolicySettings> {
    return this.http.get<PolicySettings>(`${BASE}/policies/template`, { params: toHttpParams({ mode }) });
  }

  create(request: CreatePolicyRequest): Observable<PolicyView> {
    return this.http.post<PolicyView>(`${BASE}/policies`, request, { context: silentErrorContext() });
  }

  update(id: string, name: string, settings: PolicySettings): Observable<PolicyView> {
    return this.http.put<PolicyView>(`${BASE}/policies/${id}`, { name, settings }, { context: silentErrorContext() });
  }

  newVersion(id: string): Observable<PolicyView> {
    return this.http.post<PolicyView>(`${BASE}/policies/${id}/versions`, null, { context: silentErrorContext() });
  }

  publish(id: string): Observable<PolicyView> {
    return this.http.post<PolicyView>(`${BASE}/policies/${id}/publish`, null, { context: silentErrorContext() });
  }

  retire(id: string, reason: string): Observable<PolicyView> {
    return this.http.post<PolicyView>(`${BASE}/policies/${id}/retire`, { reason }, { context: silentErrorContext() });
  }

  /** Desligamento imediato da carteira (versão OFF publicada na hora + transferência das conversas). */
  disablePortfolio(portfolioId: string, reason: string): Observable<PolicyView> {
    return this.http.post<PolicyView>(`${BASE}/portfolios/${portfolioId}/disable`, { reason }, { context: silentErrorContext() });
  }

  prompts(): Observable<PromptView[]> {
    return this.http.get<PromptView[]>(`${BASE}/prompts`);
  }
}

/** Trilha dos turnos, fila de revisão e qualidade (`ASSISTANT_REVIEW`). */
@Injectable({ providedIn: 'root' })
export class AssistantReviewApi {
  private readonly http = inject(HttpClient);

  queue(status: string | null, creditorId: string | null, page: PageRequest): Observable<PageResponse<ReviewView>> {
    return this.http.get<PageResponse<ReviewView>>(`${BASE}/reviews`, { params: toHttpParams({ status, creditorId, page: page.page, size: page.size }) });
  }

  submit(id: string, request: ReviewRequest): Observable<ReviewView> {
    return this.http.post<ReviewView>(`${BASE}/reviews/${id}`, request, { context: silentErrorContext() });
  }

  /** Detalhe com conteúdo: o servidor registra `SENSITIVE_DATA_VIEWED` no log de acesso. */
  turn(id: string): Observable<TurnDetail> {
    return this.http.get<TurnDetail>(`${BASE}/turns/${id}`);
  }

  turns(conversationId: string, page: PageRequest): Observable<PageResponse<TurnView>> {
    return this.http.get<PageResponse<TurnView>>(`${BASE}/conversations/${conversationId}/turns`, { params: toHttpParams({ page: page.page, size: page.size }) });
  }

  quality(from: string | null, to: string | null, creditorId: string | null, portfolioId: string | null): Observable<QualityView> {
    return this.http.get<QualityView>(`${BASE}/quality`, { params: toHttpParams({ from, to, creditorId, portfolioId }) });
  }
}
