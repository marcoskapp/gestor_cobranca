import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { silentErrorContext } from '../../../core/http/http-context';
import { toHttpParams } from '../../../core/http/http-params';
import { FileDownloadService } from '../../../shared/data/file-download';
import { PageRequest, PageResponse } from '../../../shared/models/page';
import {
  AttemptView,
  CreateRestrictionRequest,
  DataSubjectRequestFilter,
  DataSubjectRequestView,
  EffectivePolicyView,
  HolidayView,
  LegalBasisView,
  OpenDataSubjectRequest,
  PolicyView,
  PublishPolicyRequest,
  RegisterLegalBasisRequest,
  RestrictionFilter,
  RestrictionView,
  RetentionPolicyView,
  RetentionRunItemView,
  RetentionRunView,
} from './compliance.models';

const BASE = `${API_V1}/compliance`;

/** Restrições de contato, evidências e tentativas. Valores de contato só no corpo, nunca na URL. */
@Injectable({ providedIn: 'root' })
export class RestrictionsApi {
  private readonly http = inject(HttpClient);
  private readonly files = inject(FileDownloadService);

  search(filter: RestrictionFilter, page: PageRequest): Observable<PageResponse<RestrictionView>> {
    return this.http.get<PageResponse<RestrictionView>>(`${BASE}/restrictions`, {
      params: toHttpParams({ ...filter, page: page.page, size: page.size }),
    });
  }

  get(id: string): Observable<RestrictionView> {
    return this.http.get<RestrictionView>(`${BASE}/restrictions/${id}`);
  }

  create(request: CreateRestrictionRequest): Observable<RestrictionView> {
    return this.http.post<RestrictionView>(`${BASE}/restrictions`, request);
  }

  revoke(id: string, reason: string): Observable<RestrictionView> {
    return this.http.post<RestrictionView>(`${BASE}/restrictions/${id}/revoke`, { reason });
  }

  attachEvidence(id: string, file: File): Observable<RestrictionView> {
    const body = new FormData();
    body.append('file', file, file.name);
    return this.http.post<RestrictionView>(`${BASE}/restrictions/${id}/evidence`, body);
  }

  downloadEvidence(id: string): Observable<string> {
    return this.files.download(`${BASE}/restrictions/${id}/evidence`, 'evidencia');
  }

  /** Dossiê JSON de restrições e tentativas do devedor (registra `DATA_EXPORTED`). */
  exportDebtorEvidence(debtorId: string): Observable<string> {
    return this.files.download(`${BASE}/debtors/${debtorId}/contact-evidence`, 'dossie-contato.json');
  }

  attempts(debtorId: string, page: PageRequest): Observable<PageResponse<AttemptView>> {
    return this.http.get<PageResponse<AttemptView>>(`${BASE}/debtors/${debtorId}/contact-attempts`, {
      params: toHttpParams({ page: page.page, size: page.size }),
    });
  }
}

@Injectable({ providedIn: 'root' })
export class ContactPolicyApi {
  private readonly http = inject(HttpClient);

  publish(request: PublishPolicyRequest): Observable<PolicyView> {
    return this.http.post<PolicyView>(`${BASE}/contact-policies`, request);
  }

  effective(creditorId: string | null): Observable<EffectivePolicyView> {
    return this.http.get<EffectivePolicyView>(`${BASE}/contact-policies/effective`, {
      params: toHttpParams({ creditorId }),
    });
  }

  history(creditorId: string | null): Observable<PolicyView[]> {
    return this.http.get<PolicyView[]>(`${BASE}/contact-policies`, { params: toHttpParams({ creditorId }) });
  }
}

@Injectable({ providedIn: 'root' })
export class HolidaysApi {
  private readonly http = inject(HttpClient);

  list(from: string, to: string): Observable<HolidayView[]> {
    return this.http.get<HolidayView[]>(`${BASE}/holidays`, { params: toHttpParams({ from, to }) });
  }

  add(date: string, name: string): Observable<HolidayView> {
    return this.http.post<HolidayView>(`${BASE}/holidays`, { date, name });
  }

  remove(id: string): Observable<void> {
    return this.http.delete<void>(`${BASE}/holidays/${id}`);
  }
}

@Injectable({ providedIn: 'root' })
export class LegalBasesApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${BASE}/legal-bases`;

  listByCreditor(creditorId: string): Observable<LegalBasisView[]> {
    return this.http.get<LegalBasisView[]>(this.base, { params: toHttpParams({ creditorId }) });
  }

  /** 404 quando não há base vigente (tratado pela tela, sem notificação genérica). */
  effective(creditorId: string, portfolioId: string | null): Observable<LegalBasisView> {
    return this.http.get<LegalBasisView>(`${this.base}/effective`, {
      params: toHttpParams({ creditorId, portfolioId }),
      context: silentErrorContext(),
    });
  }

  register(request: RegisterLegalBasisRequest): Observable<LegalBasisView> {
    return this.http.post<LegalBasisView>(this.base, request);
  }

  end(id: string, lastDay: string, reason: string): Observable<LegalBasisView> {
    return this.http.post<LegalBasisView>(`${this.base}/${id}/end`, { lastDay, reason });
  }
}

/** Solicitações de titulares (LGPD art. 18). Documento do requerente só no corpo. */
@Injectable({ providedIn: 'root' })
export class DataSubjectRequestsApi {
  private readonly http = inject(HttpClient);
  private readonly files = inject(FileDownloadService);
  private readonly base = `${BASE}/data-subject-requests`;

  search(filter: DataSubjectRequestFilter, page: PageRequest): Observable<PageResponse<DataSubjectRequestView>> {
    return this.http.get<PageResponse<DataSubjectRequestView>>(this.base, {
      params: toHttpParams({ ...filter, page: page.page, size: page.size }),
    });
  }

  lookup(document: string, page: PageRequest): Observable<PageResponse<DataSubjectRequestView>> {
    return this.http.post<PageResponse<DataSubjectRequestView>>(
      `${this.base}/lookup`,
      { document },
      { params: toHttpParams({ page: page.page, size: page.size }) },
    );
  }

  get(id: string): Observable<DataSubjectRequestView> {
    return this.http.get<DataSubjectRequestView>(`${this.base}/${id}`);
  }

  open(request: OpenDataSubjectRequest): Observable<DataSubjectRequestView> {
    return this.http.post<DataSubjectRequestView>(this.base, request);
  }

  verifyIdentity(id: string, debtorId: string | null): Observable<DataSubjectRequestView> {
    return this.http.post<DataSubjectRequestView>(`${this.base}/${id}/verify-identity`, { debtorId });
  }

  start(id: string, assignee: string | null): Observable<DataSubjectRequestView> {
    return this.http.post<DataSubjectRequestView>(`${this.base}/${id}/start`, assignee ? { assignee } : null);
  }

  assign(id: string, assignee: string): Observable<DataSubjectRequestView> {
    return this.http.post<DataSubjectRequestView>(`${this.base}/${id}/assign`, { assignee });
  }

  execute(id: string): Observable<DataSubjectRequestView> {
    return this.http.post<DataSubjectRequestView>(`${this.base}/${id}/execute`, null);
  }

  complete(id: string, response: string): Observable<DataSubjectRequestView> {
    return this.http.post<DataSubjectRequestView>(`${this.base}/${id}/complete`, { response });
  }

  reject(id: string, response: string): Observable<DataSubjectRequestView> {
    return this.http.post<DataSubjectRequestView>(`${this.base}/${id}/reject`, { response });
  }

  downloadResult(id: string): Observable<string> {
    return this.files.download(`${this.base}/${id}/result`, 'dados-do-titular.json');
  }
}

@Injectable({ providedIn: 'root' })
export class RetentionApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${BASE}/retention`;

  policies(): Observable<RetentionPolicyView[]> {
    return this.http.get<RetentionPolicyView[]>(`${this.base}/policies`);
  }

  configure(category: string, retentionMonths: number, enabled: boolean): Observable<RetentionPolicyView> {
    return this.http.put<RetentionPolicyView>(`${this.base}/policies/${category}`, { retentionMonths, enabled });
  }

  run(category: string, dryRun: boolean): Observable<RetentionRunView> {
    return this.http.post<RetentionRunView>(`${this.base}/policies/${category}/run`, null, {
      params: toHttpParams({ dryRun }),
    });
  }

  runs(page: PageRequest): Observable<PageResponse<RetentionRunView>> {
    return this.http.get<PageResponse<RetentionRunView>>(`${this.base}/runs`, {
      params: toHttpParams({ page: page.page, size: page.size }),
    });
  }

  getRun(id: string): Observable<RetentionRunView> {
    return this.http.get<RetentionRunView>(`${this.base}/runs/${id}`);
  }

  runItems(id: string, page: PageRequest): Observable<PageResponse<RetentionRunItemView>> {
    return this.http.get<PageResponse<RetentionRunItemView>>(`${this.base}/runs/${id}/items`, {
      params: toHttpParams({ page: page.page, size: page.size }),
    });
  }
}
