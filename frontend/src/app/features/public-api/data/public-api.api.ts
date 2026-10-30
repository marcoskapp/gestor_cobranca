import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { silentErrorContext } from '../../../core/http/http-context';
import { toHttpParams } from '../../../core/http/http-params';
import { FileDownloadService } from '../../../shared/data/file-download';
import { PageRequest, PageResponse } from '../../../shared/models/page';
import {
  CreateTokenRequest,
  IssuedToken,
  PricePlanRequest,
  PricePlanView,
  ScopeView,
  StatementView,
  TokenFilter,
  TokenView,
  UpdateTokenRequest,
  UsageFilter,
  UsageReport,
} from './public-api.models';

const BASE = `${API_V1}/public-api`;

/** Tokens de integração da API pública (gestão interna). */
@Injectable({ providedIn: 'root' })
export class IntegrationTokensApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${BASE}/tokens`;

  search(filter: TokenFilter, page: PageRequest): Observable<PageResponse<TokenView>> {
    return this.http.get<PageResponse<TokenView>>(this.base, { params: toHttpParams({ ...filter, page: page.page, size: page.size }) });
  }

  get(id: string): Observable<TokenView> {
    return this.http.get<TokenView>(`${this.base}/${id}`);
  }

  scopes(): Observable<ScopeView[]> {
    return this.http.get<ScopeView[]>(`${this.base}/scopes`);
  }

  /** Erros de regra ficam com o diálogo. */
  create(request: CreateTokenRequest): Observable<IssuedToken> {
    return this.http.post<IssuedToken>(this.base, request, { context: silentErrorContext() });
  }

  update(id: string, request: UpdateTokenRequest): Observable<TokenView> {
    return this.http.put<TokenView>(`${this.base}/${id}`, request, { context: silentErrorContext() });
  }

  revoke(id: string, reason: string): Observable<TokenView> {
    return this.http.post<TokenView>(`${this.base}/${id}/revoke`, { reason }, { context: silentErrorContext() });
  }

  rotate(id: string, graceMinutes: number): Observable<IssuedToken> {
    return this.http.post<IssuedToken>(`${this.base}/${id}/rotate`, { graceMinutes }, { context: silentErrorContext() });
  }
}

/** Consumo, tabelas de preço e extratos da API pública. */
@Injectable({ providedIn: 'root' })
export class PublicApiUsageApi {
  private readonly http = inject(HttpClient);
  private readonly files = inject(FileDownloadService);

  report(filter: UsageFilter): Observable<UsageReport> {
    return this.http.get<UsageReport>(`${BASE}/usage`, { params: toHttpParams({ ...filter }) });
  }

  exportUsage(filter: UsageFilter): Observable<string> {
    const params = toHttpParams({ ...filter }).toString();
    return this.files.download(`${BASE}/usage/export?${params}`, `consumo-api-publica-${filter.from}-${filter.to}.csv`);
  }

  plans(): Observable<PricePlanView[]> {
    return this.http.get<PricePlanView[]>(`${BASE}/price-plans`);
  }

  createPlan(request: PricePlanRequest): Observable<PricePlanView> {
    return this.http.post<PricePlanView>(`${BASE}/price-plans`, request, { context: silentErrorContext() });
  }

  updatePlan(id: string, request: PricePlanRequest): Observable<PricePlanView> {
    return this.http.put<PricePlanView>(`${BASE}/price-plans/${id}`, request, { context: silentErrorContext() });
  }

  publishPlan(id: string): Observable<PricePlanView> {
    return this.http.post<PricePlanView>(`${BASE}/price-plans/${id}/publish`, {});
  }

  statements(creditorId: string | null, month: string | null, page: PageRequest): Observable<PageResponse<StatementView>> {
    return this.http.get<PageResponse<StatementView>>(`${BASE}/statements`, {
      params: toHttpParams({ creditorId, month, page: page.page, size: page.size }),
    });
  }

  preview(creditorId: string, month: string): Observable<StatementView> {
    return this.http.get<StatementView>(`${BASE}/statements/preview`, { params: toHttpParams({ creditorId, month }) });
  }

  close(creditorId: string, month: string, reason: string | null): Observable<StatementView> {
    return this.http.post<StatementView>(`${BASE}/statements`, { creditorId, month, reason }, { context: silentErrorContext() });
  }

  exportStatement(id: string): Observable<string> {
    return this.files.download(`${BASE}/statements/${id}/export`, `extrato-api-publica-${id}.csv`);
  }
}
