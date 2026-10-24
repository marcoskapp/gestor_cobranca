import { HttpClient, HttpContext, HttpErrorResponse, HttpResponse } from '@angular/common/http';
import { DOCUMENT, Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { publicRequestContext } from '../../core/http/http-context';
import { toApiError } from '../../core/http/problem';
import { fileNameFrom } from '../../shared/data/file-download';
import { ERROR_MESSAGES } from './portal.labels';
import {
  AcceptRequest,
  AcceptanceResult,
  AgreementDetail,
  AgreementSummary,
  ChallengeView,
  DebtsView,
  LinkResult,
  OffersView,
  PixView,
  PortalChannel,
  ServiceRequestBody,
  ServiceRequestView,
  SessionGrant,
  SessionView,
  SimulateRequest,
  SiteView,
} from './portal.models';

export const PORTAL_API = '/api/portal/v1';
const ME = `${PORTAL_API}/me`;

/**
 * API do portal. Todas as chamadas vão sem o token interno (o interceptor dos usuários ignora
 * `/api/portal/`) e com erros tratados na própria tela, em linguagem simples; o token do portal é
 * anexado pelo `portalInterceptor` só em `/api/portal/v1/me`.
 */
@Injectable({ providedIn: 'root' })
export class PortalApi {
  private readonly http = inject(HttpClient);
  private readonly document = inject(DOCUMENT);
  private readonly context = (): HttpContext => publicRequestContext();

  site(slug: string): Observable<SiteView> {
    return this.http.get<SiteView>(`${PORTAL_API}/sites/${encodeURIComponent(slug)}`, { context: this.context() });
  }

  logoUrl(slug: string, version: string | null): string {
    return `${PORTAL_API}/sites/${encodeURIComponent(slug)}/logo${version ? `?v=${version}` : ''}`;
  }

  requestCode(slug: string, document: string, channel: PortalChannel): Observable<ChallengeView> {
    return this.http.post<ChallengeView>(`${PORTAL_API}/sites/${encodeURIComponent(slug)}/access/code`,
      { document, channel }, { context: this.context() });
  }

  verify(slug: string, challengeId: string, code: string | null, knowledgeAnswer: string | null): Observable<SessionGrant> {
    return this.http.post<SessionGrant>(
      `${PORTAL_API}/sites/${encodeURIComponent(slug)}/access/challenges/${challengeId}/verify`,
      { code, knowledgeAnswer }, { context: this.context() });
  }

  redeemLink(slug: string, token: string): Observable<LinkResult> {
    return this.http.post<LinkResult>(`${PORTAL_API}/sites/${encodeURIComponent(slug)}/access/link`, { token },
      { context: this.context() });
  }

  session(): Observable<SessionView> {
    return this.http.get<SessionView>(`${ME}/session`, { context: this.context() });
  }

  refresh(): Observable<SessionGrant> {
    return this.http.post<SessionGrant>(`${ME}/session/refresh`, {}, { context: this.context() });
  }

  logout(): Observable<void> {
    return this.http.post<void>(`${ME}/session/logout`, {}, { context: this.context() });
  }

  acknowledgePrivacy(noticeVersion: number): Observable<SessionView> {
    return this.http.post<SessionView>(`${ME}/session/privacy`, { noticeVersion }, { context: this.context() });
  }

  debts(): Observable<DebtsView> {
    return this.http.get<DebtsView>(`${ME}/debts`, { context: this.context() });
  }

  offers(portfolioId: string): Observable<OffersView> {
    return this.http.get<OffersView>(`${ME}/portfolios/${portfolioId}/offers`, { context: this.context() });
  }

  simulate(portfolioId: string, request: SimulateRequest): Observable<OffersView> {
    return this.http.post<OffersView>(`${ME}/portfolios/${portfolioId}/simulations`, request, { context: this.context() });
  }

  accept(request: AcceptRequest): Observable<AcceptanceResult> {
    return this.http.post<AcceptanceResult>(`${ME}/agreements`, request, { context: this.context() });
  }

  agreements(): Observable<AgreementSummary[]> {
    return this.http.get<AgreementSummary[]>(`${ME}/agreements`, { context: this.context() });
  }

  agreement(id: string): Observable<AgreementDetail> {
    return this.http.get<AgreementDetail>(`${ME}/agreements/${id}`, { context: this.context() });
  }

  pix(agreementId: string, installmentId: string): Observable<PixView> {
    return this.http.post<PixView>(`${ME}/agreements/${agreementId}/installments/${installmentId}/pix`, {},
      { context: this.context() });
  }

  /** Termo do acordo (PDF) baixado como `blob:` temporário; nada fica em cache. */
  downloadTerm(agreementId: string): Observable<string> {
    return this.http.get(`${ME}/agreements/${agreementId}/term`, {
      observe: 'response', responseType: 'blob', context: this.context(),
    }).pipe(map((response: HttpResponse<Blob>) => {
      const name = fileNameFrom(response) ?? 'termo-do-acordo.pdf';
      this.save(response.body ?? new Blob(), name);
      return name;
    }));
  }

  requestService(body: ServiceRequestBody): Observable<ServiceRequestView> {
    return this.http.post<ServiceRequestView>(`${ME}/service-requests`, body, { context: this.context() });
  }

  serviceRequests(): Observable<ServiceRequestView[]> {
    return this.http.get<ServiceRequestView[]>(`${ME}/service-requests`, { context: this.context() });
  }

  private save(blob: Blob, name: string): void {
    const view = this.document.defaultView;
    if (!view?.URL?.createObjectURL) {
      return;
    }
    const href = view.URL.createObjectURL(blob);
    const anchor = this.document.createElement('a');
    anchor.href = href;
    anchor.download = name;
    anchor.rel = 'noopener';
    anchor.style.display = 'none';
    this.document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    view.setTimeout(() => view.URL.revokeObjectURL(href), 0);
  }
}

/** Mensagem simples para o devedor a partir do erro da API. */
export function portalMessage(error: unknown, fallback = 'Algo deu errado. Tente de novo em instantes.'): string {
  if (error instanceof HttpErrorResponse && error.status === 0) {
    return 'Sem conexão com a internet. Confira sua conexão e tente de novo.';
  }
  const api = toApiError(error);
  if (api.code && ERROR_MESSAGES[api.code]) {
    return ERROR_MESSAGES[api.code];
  }
  if (api.status >= 500) {
    return api.requestId ? `${fallback} Se precisar de ajuda, informe o código ${api.requestId}.` : fallback;
  }
  return api.detail ?? fallback;
}

/** Segundos até liberar nova tentativa (bloqueio progressivo), quando informado. */
export function retryAfterSeconds(error: unknown): number | null {
  if (!(error instanceof HttpErrorResponse) || error.status !== 429) {
    return null;
  }
  const body = error.error as { retryAfterSeconds?: number } | null;
  return typeof body?.retryAfterSeconds === 'number' ? body.retryAfterSeconds : null;
}
