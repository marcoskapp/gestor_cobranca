import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { silentErrorContext } from '../../../core/http/http-context';
import { toHttpParams } from '../../../core/http/http-params';
import { PageRequest, PageResponse } from '../../../shared/models/page';
import {
  AccountComparison,
  AgentView,
  CreateTelephonyAccountRequest,
  EndpointView,
  RouteView,
  SaveRouteRequest,
  TelephonyAccountView,
  TelephonyProviderView,
  UpdateTelephonyAccountRequest,
  CallFilter,
  CallView,
  CampaignSettings,
  CampaignStats,
  CampaignView,
  CreateCampaignRequest,
  EntryView,
  PauseReason,
  RecordingPurpose,
  TabulationRequest,
} from './telephony.models';

const BASE = `${API_V1}/telephony`;

/** Presença do próprio operador (`/presence/me`) e operadores/ramais (supervisão e gestão). */
@Injectable({ providedIn: 'root' })
export class PresenceApi {
  private readonly http = inject(HttpClient);

  /** 409 `telephony.agent_not_configured` quando o usuário não tem ramal: a tela trata em silêncio. */
  me(): Observable<AgentView> {
    return this.http.get<AgentView>(`${BASE}/presence/me`, { context: silentErrorContext() });
  }

  login(campaignId: string | null): Observable<AgentView> {
    return this.http.post<AgentView>(`${BASE}/presence/me/login`, { campaignId }, { context: silentErrorContext() });
  }

  available(): Observable<AgentView> {
    return this.http.post<AgentView>(`${BASE}/presence/me/available`, null, { context: silentErrorContext() });
  }

  pause(reason: PauseReason): Observable<AgentView> {
    return this.http.post<AgentView>(`${BASE}/presence/me/pause`, { reason }, { context: silentErrorContext() });
  }

  joinCampaign(campaignId: string | null): Observable<AgentView> {
    return this.http.post<AgentView>(`${BASE}/presence/me/campaign`, { campaignId }, { context: silentErrorContext() });
  }

  /** Sinal periódico (30 s); sem sinal por 90 s o servidor tira o operador da telefonia. */
  heartbeat(): Observable<void> {
    return this.http.post<void>(`${BASE}/presence/me/heartbeat`, null, { context: silentErrorContext() });
  }

  logout(): Observable<AgentView> {
    return this.http.post<AgentView>(`${BASE}/presence/me/logout`, null, { context: silentErrorContext() });
  }
}

@Injectable({ providedIn: 'root' })
export class AgentsApi {
  private readonly http = inject(HttpClient);

  list(): Observable<AgentView[]> {
    return this.http.get<AgentView[]>(`${BASE}/agents`);
  }

  configure(userId: string, extension: string, active: boolean): Observable<AgentView> {
    return this.http.put<AgentView>(`${BASE}/agents/${userId}`, { extension, active }, { context: silentErrorContext() });
  }

  forceLogout(userId: string, reason: string): Observable<AgentView> {
    return this.http.post<AgentView>(`${BASE}/agents/${userId}/logout`, { reason }, { context: silentErrorContext() });
  }

  /** Ramais por conta (plataforma) de todos os operadores. */
  endpoints(): Observable<EndpointView[]> {
    return this.http.get<EndpointView[]>(`${BASE}/agents/endpoints`);
  }

  /** `active = false` marca o operador como fora da plataforma da conta. */
  configureEndpoint(userId: string, accountId: string, extension: string | null, active: boolean): Observable<EndpointView> {
    return this.http.put<EndpointView>(`${BASE}/agents/${userId}/endpoints/${accountId}`, { extension, active }, { context: silentErrorContext() });
  }

  /** Volta a usar o ramal padrão do operador nesta conta. */
  removeEndpoint(userId: string, accountId: string): Observable<void> {
    return this.http.delete<void>(`${BASE}/agents/${userId}/endpoints/${accountId}`, { context: silentErrorContext() });
  }
}

/** Contas de telefonia (várias, de provedores diferentes), catálogo de provedores e comparação. */
@Injectable({ providedIn: 'root' })
export class TelephonyAccountsApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${BASE}/accounts`;

  list(): Observable<TelephonyAccountView[]> {
    return this.http.get<TelephonyAccountView[]>(this.base);
  }

  providers(): Observable<TelephonyProviderView[]> {
    return this.http.get<TelephonyProviderView[]>(`${this.base}/providers`);
  }

  create(request: CreateTelephonyAccountRequest): Observable<TelephonyAccountView> {
    return this.http.post<TelephonyAccountView>(this.base, request, { context: silentErrorContext() });
  }

  update(id: string, request: UpdateTelephonyAccountRequest): Observable<TelephonyAccountView> {
    return this.http.put<TelephonyAccountView>(`${this.base}/${id}`, request, { context: silentErrorContext() });
  }

  rotateCredentials(id: string, credentials: Record<string, string>): Observable<TelephonyAccountView> {
    return this.http.put<TelephonyAccountView>(`${this.base}/${id}/credentials`, credentials, { context: silentErrorContext() });
  }

  activate(id: string): Observable<TelephonyAccountView> {
    return this.http.post<TelephonyAccountView>(`${this.base}/${id}/activate`, null, { context: silentErrorContext() });
  }

  deactivate(id: string): Observable<TelephonyAccountView> {
    return this.http.post<TelephonyAccountView>(`${this.base}/${id}/deactivate`, null, { context: silentErrorContext() });
  }

  /** Datas `AAAA-MM-DD` (dias de operação, inclusive); sem datas, os últimos 30 dias. */
  comparison(from: string | null, to: string | null): Observable<AccountComparison[]> {
    return this.http.get<AccountComparison[]>(`${this.base}/comparison`, { params: toHttpParams({ from, to }) });
  }
}

/** Roteamento entre contas: uma regra por escopo (campanha, carteira, credor ou padrão). */
@Injectable({ providedIn: 'root' })
export class TelephonyRoutingApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${BASE}/routes`;

  list(): Observable<RouteView[]> {
    return this.http.get<RouteView[]>(this.base);
  }

  save(request: SaveRouteRequest): Observable<RouteView> {
    return this.http.put<RouteView>(this.base, request, { context: silentErrorContext() });
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`, { context: silentErrorContext() });
  }
}

/** Chamadas: clique para ligar, consulta, encerramento, tabulação e gravação. */
@Injectable({ providedIn: 'root' })
export class CallsApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${BASE}/calls`;

  /** 201 com `QUEUED` ou com `FAILED/CONTACT_DENIED` (motivos e próximo horário) quando o porteiro nega. */
  clickToCall(caseId: string, contactId: string | null, key: string): Observable<CallView> {
    return this.http.post<CallView>(this.base, { caseId, contactId }, { headers: new HttpHeaders({ 'Idempotency-Key': key }), context: silentErrorContext() });
  }

  get(id: string): Observable<CallView> {
    return this.http.get<CallView>(`${this.base}/${id}`, { context: silentErrorContext() });
  }

  search(filter: CallFilter, page: PageRequest): Observable<PageResponse<CallView>> {
    return this.http.get<PageResponse<CallView>>(this.base, { params: toHttpParams({ ...filter, page: page.page, size: page.size }) });
  }

  awaitingTabulation(): Observable<CallView[]> {
    return this.http.get<CallView[]>(`${this.base}/awaiting-tabulation`, { context: silentErrorContext() });
  }

  hangup(id: string): Observable<CallView> {
    return this.http.post<CallView>(`${this.base}/${id}/hangup`, null, { context: silentErrorContext() });
  }

  tabulate(id: string, request: TabulationRequest): Observable<CallView> {
    return this.http.post<CallView>(`${this.base}/${id}/tabulation`, request, { context: silentErrorContext() });
  }

  /**
   * Áudio da gravação para o player (blob em memória, nunca em cache). O servidor exige
   * `CALL_RECORDING_LISTEN` e a finalidade e registra `FILE_DOWNLOADED` no log de acesso.
   */
  recording(id: string, purpose: RecordingPurpose): Observable<{ blob: Blob; sha256: string | null }> {
    return this.http
      .get(`${this.base}/${id}/recording`, { params: toHttpParams({ purpose }), observe: 'response', responseType: 'blob', context: silentErrorContext() })
      .pipe(map((r) => ({ blob: r.body ?? new Blob(), sha256: r.headers.get('X-Content-SHA256') })));
  }
}

/** Campanhas de discagem: configuração (gestão), operação e indicadores (supervisão). */
@Injectable({ providedIn: 'root' })
export class DialerCampaignsApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${BASE}/dialer-campaigns`;

  list(): Observable<CampaignView[]> {
    return this.http.get<CampaignView[]>(this.base);
  }

  get(id: string): Observable<CampaignView> {
    return this.http.get<CampaignView>(`${this.base}/${id}`);
  }

  create(request: CreateCampaignRequest): Observable<CampaignView> {
    return this.http.post<CampaignView>(this.base, request, { context: silentErrorContext() });
  }

  update(id: string, settings: CampaignSettings): Observable<CampaignView> {
    return this.http.put<CampaignView>(`${this.base}/${id}`, settings, { context: silentErrorContext() });
  }

  action(id: string, action: 'activate' | 'pause' | 'finish' | 'reload'): Observable<CampaignView> {
    return this.http.post<CampaignView>(`${this.base}/${id}/${action}`, null, { context: silentErrorContext() });
  }

  entries(id: string, status: string | null, page: PageRequest): Observable<PageResponse<EntryView>> {
    return this.http.get<PageResponse<EntryView>>(`${this.base}/${id}/entries`, { params: toHttpParams({ status, page: page.page, size: page.size }) });
  }

  /** Indicadores do dia (consulta frequente do painel: erros não viram aviso a cada ciclo). */
  stats(id: string): Observable<CampaignStats> {
    return this.http.get<CampaignStats>(`${this.base}/${id}/stats`, { context: silentErrorContext() });
  }
}
