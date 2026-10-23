import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { toHttpParams } from '../../../core/http/http-params';
import { FileDownloadService } from '../../../shared/data/file-download';
import { PageRequest, PageResponse } from '../../../shared/models/page';
import {
  AgreementLinkView,
  AssessmentInput,
  AssessmentRequest,
  AssessmentView,
  AssignRequest,
  AuthorizationDecisionRequest,
  AuthorizationRequest,
  AuthorizationView,
  DeadlineFilter,
  DeadlineView,
  DocumentRequest,
  DocumentView,
  ExpenseView,
  HearingView,
  LawFirmRequest,
  LawFirmView,
  LawsuitView,
  LegalDashboard,
  LegalSettings,
  MatterFilter,
  MatterSheet,
  MatterView,
  MeasureView,
  MemberView,
  MovementResult,
  MovementView,
  ReferRequest,
  RuleRequest,
  RuleView,
  SuspensionView,
  TransitionRequest,
  ViabilityResultView,
} from './legal.models';

const BASE = `${API_V1}/legal`;
type Body = Record<string, unknown>;

/** Casos jurídicos: indicação, ficha, fases, responsável, viabilidade, autorização e painel. */
@Injectable({ providedIn: 'root' })
export class LegalMattersApi {
  private readonly http = inject(HttpClient);

  dashboard(creditorId: string | null): Observable<LegalDashboard> {
    return this.http.get<LegalDashboard>(`${BASE}/dashboard`, { params: toHttpParams({ creditorId }) });
  }

  search(filter: MatterFilter, page: PageRequest): Observable<PageResponse<MatterView>> {
    return this.http.get<PageResponse<MatterView>>(`${BASE}/matters`, { params: toHttpParams({ ...filter, page: page.page, size: page.size }) });
  }

  refer(request: ReferRequest): Observable<MatterView> {
    return this.http.post<MatterView>(`${BASE}/matters`, request);
  }

  sheet(id: string): Observable<MatterSheet> {
    return this.http.get<MatterSheet>(`${BASE}/matters/${id}/sheet`);
  }

  transition(id: string, request: TransitionRequest): Observable<MatterView> {
    return this.http.post<MatterView>(`${BASE}/matters/${id}/transitions`, request);
  }

  assign(id: string, request: AssignRequest): Observable<MatterView> {
    return this.http.put<MatterView>(`${BASE}/matters/${id}/responsible`, request);
  }

  agreements(id: string): Observable<AgreementLinkView[]> {
    return this.http.get<AgreementLinkView[]>(`${BASE}/matters/${id}/agreements`);
  }

  previewViability(id: string, input: AssessmentInput): Observable<ViabilityResultView> {
    return this.http.post<ViabilityResultView>(`${BASE}/matters/${id}/viability/preview`, input);
  }

  recordViability(id: string, request: AssessmentRequest): Observable<AssessmentView> {
    return this.http.post<AssessmentView>(`${BASE}/matters/${id}/viability`, request);
  }

  viabilityHistory(id: string): Observable<AssessmentView[]> {
    return this.http.get<AssessmentView[]>(`${BASE}/matters/${id}/viability`);
  }

  requestAuthorization(id: string, request: AuthorizationRequest): Observable<AuthorizationView> {
    return this.http.post<AuthorizationView>(`${BASE}/matters/${id}/authorizations`, request);
  }

  decideAuthorization(authorizationId: string, request: AuthorizationDecisionRequest): Observable<AuthorizationView> {
    return this.http.post<AuthorizationView>(`${BASE}/authorizations/${authorizationId}/decision`, request);
  }
}

/** Medidas extrajudiciais, processos, andamentos e audiências. */
@Injectable({ providedIn: 'root' })
export class LegalProceedingsApi {
  private readonly http = inject(HttpClient);

  registerMeasure(matterId: string, body: Body): Observable<MeasureView> {
    return this.http.post<MeasureView>(`${BASE}/matters/${matterId}/measures`, body);
  }

  submitMeasure(id: string, body: Body): Observable<MeasureView> {
    return this.http.post<MeasureView>(`${BASE}/measures/${id}/submission`, body);
  }

  measureStatus(id: string, body: Body): Observable<MeasureView> {
    return this.http.post<MeasureView>(`${BASE}/measures/${id}/status`, body);
  }

  registerLawsuit(matterId: string, body: Body): Observable<LawsuitView> {
    return this.http.post<LawsuitView>(`${BASE}/matters/${matterId}/lawsuits`, body);
  }

  updateLawsuit(id: string, body: Body): Observable<LawsuitView> {
    return this.http.put<LawsuitView>(`${BASE}/lawsuits/${id}`, body);
  }

  lawsuitStatus(id: string, body: Body): Observable<LawsuitView> {
    return this.http.post<LawsuitView>(`${BASE}/lawsuits/${id}/status`, body);
  }

  movements(id: string, page: PageRequest): Observable<PageResponse<MovementView>> {
    return this.http.get<PageResponse<MovementView>>(`${BASE}/lawsuits/${id}/movements`, { params: toHttpParams({ page: page.page, size: page.size }) });
  }

  recordMovement(id: string, body: Body): Observable<MovementResult> {
    return this.http.post<MovementResult>(`${BASE}/lawsuits/${id}/movements`, body);
  }

  hearings(lawsuitId: string): Observable<HearingView[]> {
    return this.http.get<HearingView[]>(`${BASE}/lawsuits/${lawsuitId}/hearings`);
  }

  scheduleHearing(lawsuitId: string, body: Body): Observable<HearingView> {
    return this.http.post<HearingView>(`${BASE}/lawsuits/${lawsuitId}/hearings`, body);
  }

  rescheduleHearing(id: string, body: Body): Observable<HearingView> {
    return this.http.put<HearingView>(`${BASE}/hearings/${id}`, body);
  }

  hearingResult(id: string, body: Body): Observable<HearingView> {
    return this.http.post<HearingView>(`${BASE}/hearings/${id}/result`, body);
  }

  cancelHearing(id: string, reason: string): Observable<HearingView> {
    return this.http.post<HearingView>(`${BASE}/hearings/${id}/cancel`, { reason });
  }
}

/** Prazos, suspensões forenses, custas, documentos e escritórios parceiros. */
@Injectable({ providedIn: 'root' })
export class LegalOperationsApi {
  private readonly http = inject(HttpClient);
  private readonly files = inject(FileDownloadService);

  deadlines(filter: DeadlineFilter, page: PageRequest): Observable<PageResponse<DeadlineView>> {
    return this.http.get<PageResponse<DeadlineView>>(`${BASE}/deadlines`, { params: toHttpParams({ ...filter, page: page.page, size: page.size }) });
  }

  createDeadline(matterId: string, body: Body): Observable<DeadlineView> {
    return this.http.post<DeadlineView>(`${BASE}/matters/${matterId}/deadlines`, body);
  }

  completeDeadline(id: string, completedOn: string, note: string | null): Observable<DeadlineView> {
    return this.http.post<DeadlineView>(`${BASE}/deadlines/${id}/complete`, { completedOn, note });
  }

  cancelDeadline(id: string, reason: string): Observable<DeadlineView> {
    return this.http.post<DeadlineView>(`${BASE}/deadlines/${id}/cancel`, { reason });
  }

  suspensions(): Observable<SuspensionView[]> {
    return this.http.get<SuspensionView[]>(`${BASE}/court-suspensions`);
  }

  addSuspension(body: Body): Observable<SuspensionView> {
    return this.http.post<SuspensionView>(`${BASE}/court-suspensions`, body);
  }

  removeSuspension(id: string, reason: string): Observable<void> {
    return this.http.delete<void>(`${BASE}/court-suspensions/${id}`, { params: toHttpParams({ reason }) });
  }

  recordExpense(matterId: string, body: Body): Observable<ExpenseView> {
    return this.http.post<ExpenseView>(`${BASE}/matters/${matterId}/expenses`, body);
  }

  cancelExpense(id: string, reason: string): Observable<ExpenseView> {
    return this.http.post<ExpenseView>(`${BASE}/expenses/${id}/cancel`, { reason });
  }

  reimburseExpense(id: string, reimbursedOn: string, note: string | null): Observable<ExpenseView> {
    return this.http.post<ExpenseView>(`${BASE}/expenses/${id}/reimburse`, { reimbursedOn, note });
  }

  upload(matterId: string, document: DocumentRequest, file: File): Observable<DocumentView> {
    const body = new FormData();
    body.append('document', new Blob([JSON.stringify(document)], { type: 'application/json' }));
    body.append('file', file, file.name);
    return this.http.post<DocumentView>(`${BASE}/matters/${matterId}/documents`, body);
  }

  download(doc: DocumentView): Observable<string> {
    return this.files.download(`${BASE}/documents/${doc.id}/content`, doc.fileName);
  }

  firms(): Observable<LawFirmView[]> {
    return this.http.get<LawFirmView[]>(`${BASE}/law-firms`);
  }

  createFirm(request: LawFirmRequest): Observable<LawFirmView> {
    return this.http.post<LawFirmView>(`${BASE}/law-firms`, request);
  }

  updateFirm(id: string, request: LawFirmRequest): Observable<LawFirmView> {
    return this.http.put<LawFirmView>(`${BASE}/law-firms/${id}`, request);
  }

  setFirmActive(id: string, active: boolean): Observable<LawFirmView> {
    return this.http.post<LawFirmView>(`${BASE}/law-firms/${id}/${active ? 'activate' : 'deactivate'}`, null);
  }

  members(firmId: string): Observable<MemberView[]> {
    return this.http.get<MemberView[]>(`${BASE}/law-firms/${firmId}/members`);
  }

  addMember(firmId: string, userId: string): Observable<MemberView> {
    return this.http.post<MemberView>(`${BASE}/law-firms/${firmId}/members`, { userId });
  }

  removeMember(firmId: string, userId: string): Observable<MemberView> {
    return this.http.post<MemberView>(`${BASE}/law-firms/${firmId}/members/${userId}/remove`, null);
  }
}

/** Regras de elegibilidade e parâmetros da assessoria. */
@Injectable({ providedIn: 'root' })
export class LegalSetupApi {
  private readonly http = inject(HttpClient);

  rules(): Observable<RuleView[]> {
    return this.http.get<RuleView[]>(`${BASE}/eligibility-rules`);
  }

  createRule(request: RuleRequest): Observable<RuleView> {
    return this.http.post<RuleView>(`${BASE}/eligibility-rules`, request);
  }

  updateRule(id: string, request: RuleRequest): Observable<RuleView> {
    return this.http.put<RuleView>(`${BASE}/eligibility-rules/${id}`, request);
  }

  setRuleActive(id: string, active: boolean): Observable<RuleView> {
    return this.http.post<RuleView>(`${BASE}/eligibility-rules/${id}/${active ? 'activate' : 'deactivate'}`, null);
  }

  settings(): Observable<LegalSettings> {
    return this.http.get<LegalSettings>(`${BASE}/settings`);
  }

  updateSettings(request: LegalSettings): Observable<LegalSettings> {
    return this.http.put<LegalSettings>(`${BASE}/settings`, request);
  }
}
