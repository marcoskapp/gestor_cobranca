import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { silentErrorContext } from '../../../core/http/http-context';
import { toHttpParams } from '../../../core/http/http-params';
import { PageRequest, PageResponse } from '../../../shared/models/page';
import {
  AssignAuthorityRequest,
  AuthorityAssignmentView,
  CreatePolicyRequest,
  EffectiveAuthorityView,
  EffectivePolicyView,
  IndexView,
  PolicyTerms,
  PolicyView,
  ProfileRequest,
  ProfileView,
  RateView,
  SimulateRequest,
  SimulationView,
  SubjectType,
  UpdatePolicyRequest,
  ValidateRequest,
  ValidationView,
} from './negotiation.models';

const BASE = `${API_V1}/negotiation`;

/** Políticas de cobrança e negociação versionadas por credor. */
@Injectable({ providedIn: 'root' })
export class PoliciesApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${BASE}/policies`;

  search(filter: { creditorId?: string | null; status?: string | null }, page: PageRequest): Observable<PageResponse<PolicyView>> {
    return this.http.get<PageResponse<PolicyView>>(this.base, { params: toHttpParams({ ...filter, page: page.page, size: page.size }) });
  }

  get(id: string): Observable<PolicyView> {
    return this.http.get<PolicyView>(`${this.base}/${id}`);
  }

  /** Modelo de termos com parâmetros usuais de mercado. */
  template(): Observable<PolicyTerms> {
    return this.http.get<PolicyTerms>(`${this.base}/template`);
  }

  /** 404 quando o credor não tem versão vigente (tratado pela tela). */
  effective(creditorId: string, portfolioId: string | null, daysOverdue: number | null, date: string | null): Observable<EffectivePolicyView> {
    return this.http.get<EffectivePolicyView>(`${this.base}/effective`, {
      params: toHttpParams({ creditorId, portfolioId, daysOverdue, date }),
      context: silentErrorContext(),
    });
  }

  create(request: CreatePolicyRequest): Observable<PolicyView> {
    return this.http.post<PolicyView>(this.base, request);
  }

  update(id: string, request: UpdatePolicyRequest): Observable<PolicyView> {
    return this.http.put<PolicyView>(`${this.base}/${id}`, request);
  }

  newVersion(id: string): Observable<PolicyView> {
    return this.http.post<PolicyView>(`${this.base}/${id}/versions`, null);
  }

  publish(id: string, effectiveFrom: string): Observable<PolicyView> {
    return this.http.post<PolicyView>(`${this.base}/${id}/publish`, { effectiveFrom });
  }

  retire(id: string, effectiveTo: string): Observable<PolicyView> {
    return this.http.post<PolicyView>(`${this.base}/${id}/retire`, { effectiveTo });
  }
}

/** Tabela manual de índices de correção (variações mensais por competência "yyyy-MM"). */
@Injectable({ providedIn: 'root' })
export class IndexesApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${BASE}/indexes`;

  list(): Observable<IndexView[]> {
    return this.http.get<IndexView[]>(this.base);
  }

  get(code: string): Observable<IndexView> {
    return this.http.get<IndexView>(`${this.base}/${code}`);
  }

  create(code: string, name: string, source: string | null): Observable<IndexView> {
    return this.http.post<IndexView>(this.base, { code, name, source });
  }

  update(code: string, name: string, source: string | null): Observable<IndexView> {
    return this.http.put<IndexView>(`${this.base}/${code}`, { name, source });
  }

  putRates(code: string, rates: RateView[]): Observable<IndexView> {
    return this.http.put<IndexView>(`${this.base}/${code}/rates`, { rates });
  }
}

/** Perfis de alçada e atribuição. */
@Injectable({ providedIn: 'root' })
export class AuthorityApi {
  private readonly http = inject(HttpClient);

  profiles(): Observable<ProfileView[]> {
    return this.http.get<ProfileView[]>(`${BASE}/authority-profiles`);
  }

  createProfile(request: ProfileRequest): Observable<ProfileView> {
    return this.http.post<ProfileView>(`${BASE}/authority-profiles`, request);
  }

  updateProfile(id: string, request: ProfileRequest): Observable<ProfileView> {
    return this.http.put<ProfileView>(`${BASE}/authority-profiles/${id}`, request);
  }

  assignments(subjectType: SubjectType | null, subjectId: string | null): Observable<AuthorityAssignmentView[]> {
    return this.http.get<AuthorityAssignmentView[]>(`${BASE}/authority-assignments`, { params: toHttpParams({ subjectType, subjectId }) });
  }

  assign(request: AssignAuthorityRequest): Observable<AuthorityAssignmentView> {
    return this.http.post<AuthorityAssignmentView>(`${BASE}/authority-assignments`, request);
  }

  unassign(id: string): Observable<void> {
    return this.http.delete<void>(`${BASE}/authority-assignments/${id}`);
  }

  effective(subjectType: SubjectType, subjectId: string, creditorId: string): Observable<EffectiveAuthorityView> {
    return this.http.get<EffectiveAuthorityView>(`${BASE}/authority/effective`, { params: toHttpParams({ subjectType, subjectId, creditorId }) });
  }

  /** Alçada do usuário autenticado para o credor. */
  mine(creditorId: string): Observable<EffectiveAuthorityView> {
    return this.http.get<EffectiveAuthorityView>(`${BASE}/authority/me`, { params: toHttpParams({ creditorId }) });
  }
}

/** Simulação (registrada com memória de cálculo) e validação de condições pelo `NegotiationGuard`. */
@Injectable({ providedIn: 'root' })
export class SimulationApi {
  private readonly http = inject(HttpClient);

  simulate(request: SimulateRequest): Observable<SimulationView> {
    return this.http.post<SimulationView>(`${BASE}/simulations`, request);
  }

  get(id: string): Observable<SimulationView> {
    return this.http.get<SimulationView>(`${BASE}/simulations/${id}`);
  }

  validate(request: ValidateRequest): Observable<ValidationView> {
    return this.http.post<ValidationView>(`${BASE}/validations`, request);
  }
}
