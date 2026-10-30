import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { silentErrorContext } from '../../../core/http/http-context';
import { toHttpParams } from '../../../core/http/http-params';
import { PageRequest, PageResponse } from '../../../shared/models/page';
import {
  BusinessUnitView,
  ContractTermsRequest,
  ContractView,
  CreateContractRequest,
  CreatePortfolioRequest,
  CreditorRequest,
  CreditorView,
  IntegrationPricingRequest,
  IntegrationPricingView,
  PortfolioView,
  TenantSettingsRequest,
  TenantView,
  UpdatePortfolioRequest,
} from './organization.models';

@Injectable({ providedIn: 'root' })
export class TenantSettingsApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${API_V1}/organization/tenant`;

  current(): Observable<TenantView> {
    return this.http.get<TenantView>(this.base);
  }

  update(request: TenantSettingsRequest): Observable<TenantView> {
    return this.http.put<TenantView>(this.base, request);
  }
}

@Injectable({ providedIn: 'root' })
export class BusinessUnitsApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${API_V1}/organization/units`;

  list(): Observable<BusinessUnitView[]> {
    return this.http.get<BusinessUnitView[]>(this.base);
  }

  create(code: string, name: string): Observable<BusinessUnitView> {
    return this.http.post<BusinessUnitView>(this.base, { code, name });
  }

  update(id: string, name: string, active: boolean): Observable<BusinessUnitView> {
    return this.http.put<BusinessUnitView>(`${this.base}/${id}`, { name, active });
  }
}

@Injectable({ providedIn: 'root' })
export class CreditorsApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${API_V1}/creditors`;

  search(filter: { q?: string | null; status?: string | null }, page: PageRequest): Observable<PageResponse<CreditorView>> {
    return this.http.get<PageResponse<CreditorView>>(this.base, {
      params: toHttpParams({ ...filter, page: page.page, size: page.size }),
    });
  }

  get(id: string): Observable<CreditorView> {
    return this.http.get<CreditorView>(`${this.base}/${id}`);
  }

  create(request: CreditorRequest): Observable<CreditorView> {
    return this.http.post<CreditorView>(this.base, request);
  }

  update(id: string, request: CreditorRequest): Observable<CreditorView> {
    return this.http.put<CreditorView>(`${this.base}/${id}`, request);
  }

  setActive(id: string, active: boolean): Observable<CreditorView> {
    return this.http.post<CreditorView>(`${this.base}/${id}/${active ? 'activate' : 'deactivate'}`, null);
  }
}

@Injectable({ providedIn: 'root' })
export class ContractsApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${API_V1}/contracts`;

  listByCreditor(creditorId: string): Observable<ContractView[]> {
    return this.http.get<ContractView[]>(this.base, { params: toHttpParams({ creditorId }) });
  }

  create(request: CreateContractRequest): Observable<ContractView> {
    return this.http.post<ContractView>(this.base, request);
  }

  updateTerms(id: string, terms: ContractTermsRequest): Observable<ContractView> {
    return this.http.put<ContractView>(`${this.base}/${id}/terms`, terms);
  }

  terminate(id: string, endDate: string, reason: string): Observable<ContractView> {
    return this.http.post<ContractView>(`${this.base}/${id}/terminate`, { endDate, reason });
  }

  /** Versões das condições de cobrança das integrações, da mais recente para a mais antiga. */
  integrationPricing(id: string): Observable<IntegrationPricingView[]> {
    return this.http.get<IntegrationPricingView[]>(`${this.base}/${id}/integration-pricing`);
  }

  /** Nova versão (vale a partir do próximo ciclo de consumo). Erros de regra ficam com o diálogo. */
  defineIntegrationPricing(id: string, request: IntegrationPricingRequest): Observable<IntegrationPricingView> {
    return this.http.post<IntegrationPricingView>(`${this.base}/${id}/integration-pricing`, request, {
      context: silentErrorContext(),
    });
  }
}

@Injectable({ providedIn: 'root' })
export class PortfoliosApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${API_V1}/portfolios`;

  search(
    filter: { creditorId?: string | null; status?: string | null; q?: string | null },
    page: PageRequest,
  ): Observable<PageResponse<PortfolioView>> {
    return this.http.get<PageResponse<PortfolioView>>(this.base, {
      params: toHttpParams({ ...filter, page: page.page, size: page.size }),
    });
  }

  get(id: string): Observable<PortfolioView> {
    return this.http.get<PortfolioView>(`${this.base}/${id}`);
  }

  create(request: CreatePortfolioRequest): Observable<PortfolioView> {
    return this.http.post<PortfolioView>(this.base, request);
  }

  update(id: string, request: UpdatePortfolioRequest): Observable<PortfolioView> {
    return this.http.put<PortfolioView>(`${this.base}/${id}`, request);
  }

  changeStatus(id: string, status: string, reason: string | null): Observable<PortfolioView> {
    return this.http.post<PortfolioView>(`${this.base}/${id}/status`, { status, reason });
  }
}
