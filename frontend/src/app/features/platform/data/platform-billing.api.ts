import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { silentErrorContext } from '../../../core/http/http-context';
import { toHttpParams } from '../../../core/http/http-params';
import { FileDownloadService } from '../../../shared/data/file-download';
import { PageRequest, PageResponse } from '../../../shared/models/page';
import {
  CreatePolicyRequest,
  OverviewView,
  PlatformPositionView,
  PlatformStatementView,
  PolicyTermsRequest,
  PolicyView,
} from './platform-billing.models';

/** Faturamento da plataforma às assessorias (`PlatformBillingController`, área da plataforma). */
@Injectable({ providedIn: 'root' })
export class PlatformBillingApi {
  private readonly http = inject(HttpClient);
  private readonly files = inject(FileDownloadService);
  private readonly base = `${API_V1}/platform/billing`;

  overview(): Observable<OverviewView> {
    return this.http.get<OverviewView>(`${this.base}/overview`);
  }

  policy(tenantId: string): Observable<PolicyView> {
    return this.http.get<PolicyView>(`${this.tenant(tenantId)}/policy`);
  }

  createPolicy(tenantId: string, request: CreatePolicyRequest): Observable<PolicyView> {
    return this.http.post<PolicyView>(`${this.tenant(tenantId)}/policy`, request, { context: silentErrorContext() });
  }

  addVersion(tenantId: string, terms: PolicyTermsRequest, reason: string | null): Observable<PolicyView> {
    return this.http.post<PolicyView>(`${this.tenant(tenantId)}/policy/versions`, { terms, reason }, { context: silentErrorContext() });
  }

  changeTerm(tenantId: string, startDate: string, endDate: string | null, reason: string): Observable<PolicyView> {
    return this.http.put<PolicyView>(`${this.tenant(tenantId)}/policy/term`, { startDate, endDate, reason }, { context: silentErrorContext() });
  }

  position(tenantId: string): Observable<PlatformPositionView> {
    return this.http.get<PlatformPositionView>(`${this.tenant(tenantId)}/position`);
  }

  preview(tenantId: string): Observable<PlatformStatementView> {
    return this.http.get<PlatformStatementView>(`${this.tenant(tenantId)}/statements/preview`, { context: silentErrorContext() });
  }

  statements(tenantId: string, month: string | null, page: PageRequest): Observable<PageResponse<PlatformStatementView>> {
    return this.http.get<PageResponse<PlatformStatementView>>(`${this.tenant(tenantId)}/statements`, {
      params: toHttpParams({ month, page: page.page, size: page.size }),
    });
  }

  /** `periodStart` nulo fecha o próximo ciclo encerrado; o de um ciclo já fechado gera nova revisão (com motivo). */
  close(tenantId: string, periodStart: string | null, reason: string | null): Observable<PlatformStatementView> {
    return this.http.post<PlatformStatementView>(`${this.tenant(tenantId)}/statements`, { periodStart, reason }, { context: silentErrorContext() });
  }

  exportStatement(tenantId: string, id: string): Observable<string> {
    return this.files.download(`${this.tenant(tenantId)}/statements/${id}/export`, `extrato-plataforma-${id}.csv`);
  }

  private tenant(tenantId: string): string {
    return `${this.base}/tenants/${encodeURIComponent(tenantId)}`;
  }
}

/** A cobrança da plataforma vista pela própria assessoria (`TenantPlatformBillingController`, somente leitura). */
@Injectable({ providedIn: 'root' })
export class TenantPlatformBillingApi {
  private readonly http = inject(HttpClient);
  private readonly files = inject(FileDownloadService);
  private readonly base = `${API_V1}/public-api/platform-billing`;

  policy(): Observable<PolicyView> {
    return this.http.get<PolicyView>(`${this.base}/policy`);
  }

  position(): Observable<PlatformPositionView> {
    return this.http.get<PlatformPositionView>(`${this.base}/position`);
  }

  statements(month: string | null, page: PageRequest): Observable<PageResponse<PlatformStatementView>> {
    return this.http.get<PageResponse<PlatformStatementView>>(`${this.base}/statements`, {
      params: toHttpParams({ month, page: page.page, size: page.size }),
    });
  }

  exportStatement(id: string): Observable<string> {
    return this.files.download(`${this.base}/statements/${id}/export`, `extrato-plataforma-${id}.csv`);
  }
}
