import { HttpClient, HttpContext, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { toHttpParams } from '../../../core/http/http-params';
import { FileDownloadService } from '../../../shared/data/file-download';
import { PageRequest, PageResponse } from '../../../shared/models/page';
import {
  MyDayView,
  AgendaFilter,
  AssignRequest,
  AssignmentView,
  CaseFilter,
  CaseSheet,
  CaseView,
  ContactAvailability,
  AdjustmentAction,
  AssigneeOption,
  CreditorPosition,
  CreditorRequestDraft,
  CreditorRequestRequest,
  CreditorRequestView,
  CreditorResponseRequest,
  CreditorResponseView,
  DeadlinePreview,
  DeadlineRuleView,
  DebtorNoticeDraft,
  DebtorNoticeRequest,
  DebtorNoticeView,
  DisputeDetail,
  DisputeFilter,
  DisputeOutcome,
  DisputeSettingsRequest,
  DisputeSettingsView,
  DisputeTemplateKind,
  DisputeTemplateView,
  DisputeView,
  PoolMemberView,
  RedistributionResult,
  DistributionResult,
  EvidenceView,
  InteractionRequest,
  InteractionView,
  MailReplyView,
  OpenDisputeRequest,
  PromiseView,
  QueueRequest,
  QueueSummary,
  QueueView,
  RecordResult,
  RedistributeRequest,
  ScheduleTaskRequest,
  SettingsRequest,
  SettingsView,
  TabulationRequest,
  TabulationView,
  TaskView,
} from './collection.models';

const BASE = `${API_V1}/collection`;

/** Casos: consulta, ficha e ciclo de vida (`CollectionCaseController`). */
@Injectable({ providedIn: 'root' })
export class CasesApi {
  private readonly http = inject(HttpClient);

  search(filter: CaseFilter, page: PageRequest): Observable<PageResponse<CaseView>> {
    return this.http.get<PageResponse<CaseView>>(`${BASE}/cases`, {
      params: toHttpParams({ ...filter, page: page.page, size: page.size, sort: page.sort }),
    });
  }

  get(id: string): Observable<CaseView> {
    return this.http.get<CaseView>(`${BASE}/cases/${id}`);
  }

  /** Ficha agregada do caso (devedor e contatos mascarados). */
  sheet(id: string): Observable<CaseSheet> {
    return this.http.get<CaseSheet>(`${BASE}/cases/${id}/sheet`);
  }

  /** "Posso contatar agora?": decisão do porteiro por canal, sem registrar tentativa. */
  contactAvailability(id: string, options: { channel?: string | null; contactId?: string | null } = {}, context?: HttpContext): Observable<ContactAvailability> {
    return this.http.get<ContactAvailability>(`${BASE}/cases/${id}/contact-availability`, {
      params: toHttpParams({ channel: options.channel ?? null, contactId: options.contactId ?? null }),
      context,
    });
  }

  byDebtor(debtorId: string): Observable<CaseView[]> {
    return this.http.get<CaseView[]>(`${BASE}/debtors/${debtorId}/cases`);
  }

  /** Suspender, retomar, jurídico, retorno do jurídico e encerramento manual (todos com motivo). */
  lifecycle(id: string, action: 'suspend' | 'resume' | 'legal' | 'legal-return' | 'close', reason: string): Observable<CaseView> {
    return this.http.post<CaseView>(`${BASE}/cases/${id}/${action}`, { reason });
  }

  refresh(id: string): Observable<CaseView> {
    return this.http.post<CaseView>(`${BASE}/cases/${id}/refresh`, null);
  }

  assignments(id: string, page: PageRequest): Observable<PageResponse<AssignmentView>> {
    return this.http.get<PageResponse<AssignmentView>>(`${BASE}/cases/${id}/assignments`, {
      params: toHttpParams({ page: page.page, size: page.size }),
    });
  }
}

/** "Seu dia" do início: contagens do trabalho do operador e das equipes do supervisor. */
@Injectable({ providedIn: 'root' })
export class MyDayApi {
  private readonly http = inject(HttpClient);

  get(): Observable<MyDayView> {
    return this.http.get<MyDayView>(`${BASE}/my-day`);
  }
}

/** Trabalho dos casos: próximo caso, meus casos, atribuição e distribuição. */
@Injectable({ providedIn: 'root' })
export class CaseWorkApi {
  private readonly http = inject(HttpClient);

  /** 200 com o caso entregue; 204 (nulo) quando não há caso disponível. */
  nextCase(queueId: string | null): Observable<CaseView | null> {
    return this.http
      .post<CaseView>(`${BASE}/next-case`, null, { params: toHttpParams({ queueId }), observe: 'response' })
      .pipe(map((response: HttpResponse<CaseView>) => (response.status === 204 ? null : response.body)));
  }

  myCases(page: PageRequest): Observable<PageResponse<CaseView>> {
    return this.http.get<PageResponse<CaseView>>(`${BASE}/my-cases`, { params: toHttpParams({ page: page.page, size: page.size }) });
  }

  assign(caseId: string, request: AssignRequest): Observable<CaseView> {
    return this.http.post<CaseView>(`${BASE}/cases/${caseId}/assign`, request);
  }

  release(caseId: string, reason: string | null): Observable<CaseView> {
    return this.http.post<CaseView>(`${BASE}/cases/${caseId}/release`, { reason });
  }

  takeOver(caseId: string, reason: string | null): Observable<CaseView> {
    return this.http.post<CaseView>(`${BASE}/cases/${caseId}/take-over`, { reason });
  }

  distribute(queueId: string, limit: number): Observable<DistributionResult> {
    return this.http.post<DistributionResult>(`${BASE}/queues/${queueId}/distribute`, { limit });
  }

  redistribute(request: RedistributeRequest): Observable<DistributionResult> {
    return this.http.post<DistributionResult>(`${BASE}/redistributions`, request);
  }
}

@Injectable({ providedIn: 'root' })
export class QueuesApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${BASE}/queues`;

  list(): Observable<QueueView[]> {
    return this.http.get<QueueView[]>(this.base);
  }

  get(id: string): Observable<QueueView> {
    return this.http.get<QueueView>(`${this.base}/${id}`);
  }

  summary(id: string): Observable<QueueSummary> {
    return this.http.get<QueueSummary>(`${this.base}/${id}/summary`);
  }

  create(request: QueueRequest): Observable<QueueView> {
    return this.http.post<QueueView>(this.base, request);
  }

  update(id: string, request: QueueRequest): Observable<QueueView> {
    return this.http.put<QueueView>(`${this.base}/${id}`, request);
  }

  setActive(id: string, active: boolean): Observable<QueueView> {
    return this.http.post<QueueView>(`${this.base}/${id}/${active ? 'activate' : 'deactivate'}`, null);
  }

  /** Reprocessa o roteamento de todos os casos ativos (202, em segundo plano). */
  reroute(): Observable<void> {
    return this.http.post<void>(`${this.base}/reroute`, null);
  }
}

@Injectable({ providedIn: 'root' })
export class TabulationsApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${BASE}/tabulations`;

  list(activeOnly: boolean): Observable<TabulationView[]> {
    return this.http.get<TabulationView[]>(this.base, { params: toHttpParams({ activeOnly }) });
  }

  create(request: TabulationRequest): Observable<TabulationView> {
    return this.http.post<TabulationView>(this.base, request);
  }

  update(id: string, request: TabulationRequest): Observable<TabulationView> {
    return this.http.put<TabulationView>(`${this.base}/${id}`, request);
  }

  setActive(id: string, active: boolean): Observable<TabulationView> {
    return this.http.post<TabulationView>(`${this.base}/${id}/${active ? 'activate' : 'deactivate'}`, null);
  }
}

@Injectable({ providedIn: 'root' })
export class InteractionsApi {
  private readonly http = inject(HttpClient);

  record(caseId: string, request: InteractionRequest): Observable<RecordResult> {
    return this.http.post<RecordResult>(`${BASE}/cases/${caseId}/interactions`, request);
  }

  list(caseId: string, page: PageRequest): Observable<PageResponse<InteractionView>> {
    return this.http.get<PageResponse<InteractionView>>(`${BASE}/cases/${caseId}/interactions`, {
      params: toHttpParams({ page: page.page, size: page.size }),
    });
  }
}

/** Tarefas, retornos e agenda (datas no fuso de operação). */
@Injectable({ providedIn: 'root' })
export class TasksApi {
  private readonly http = inject(HttpClient);

  schedule(caseId: string, request: ScheduleTaskRequest): Observable<TaskView> {
    return this.http.post<TaskView>(`${BASE}/cases/${caseId}/tasks`, request);
  }

  byCase(caseId: string): Observable<TaskView[]> {
    return this.http.get<TaskView[]>(`${BASE}/cases/${caseId}/tasks`);
  }

  agenda(filter: AgendaFilter, page: PageRequest): Observable<PageResponse<TaskView>> {
    return this.http.get<PageResponse<TaskView>>(`${BASE}/agenda`, {
      params: toHttpParams({ ...filter, page: page.page, size: page.size }),
    });
  }

  complete(id: string, interactionId: string | null): Observable<TaskView> {
    return this.http.post<TaskView>(`${BASE}/tasks/${id}/complete`, { interactionId });
  }

  cancel(id: string, reason: string): Observable<TaskView> {
    return this.http.post<TaskView>(`${BASE}/tasks/${id}/cancel`, { reason });
  }

  reschedule(id: string, dueAt: string): Observable<TaskView> {
    return this.http.post<TaskView>(`${BASE}/tasks/${id}/reschedule`, { dueAt });
  }

  reassign(id: string, userId: string | null): Observable<TaskView> {
    return this.http.post<TaskView>(`${BASE}/tasks/${id}/reassign`, { userId });
  }
}

@Injectable({ providedIn: 'root' })
export class PromisesApi {
  private readonly http = inject(HttpClient);

  byCase(caseId: string): Observable<PromiseView[]> {
    return this.http.get<PromiseView[]>(`${BASE}/cases/${caseId}/promises`);
  }

  cancel(id: string, reason: string): Observable<PromiseView> {
    return this.http.post<PromiseView>(`${BASE}/promises/${id}/cancel`, { reason });
  }
}

/** Contestações (F21) e evidências; o download registra `FILE_DOWNLOADED`. */
@Injectable({ providedIn: 'root' })
export class DisputesApi {
  private readonly http = inject(HttpClient);
  private readonly files = inject(FileDownloadService);

  open(caseId: string, request: OpenDisputeRequest): Observable<DisputeView> {
    return this.http.post<DisputeView>(`${BASE}/cases/${caseId}/disputes`, request);
  }

  byCase(caseId: string): Observable<DisputeView[]> {
    return this.http.get<DisputeView[]>(`${BASE}/cases/${caseId}/disputes`);
  }

  search(filter: DisputeFilter, page: PageRequest): Observable<PageResponse<DisputeView>> {
    return this.http.get<PageResponse<DisputeView>>(`${BASE}/disputes`, {
      params: toHttpParams({ ...filter, page: page.page, size: page.size }),
    });
  }

  get(id: string): Observable<DisputeView> {
    return this.http.get<DisputeView>(`${BASE}/disputes/${id}`);
  }

  review(id: string): Observable<DisputeView> {
    return this.http.post<DisputeView>(`${BASE}/disputes/${id}/review`, null);
  }

  /** @param adjustmentAction nulo = a sugerida pelo tipo; NONE = nenhuma */
  resolve(id: string, outcome: DisputeOutcome, note: string, adjustmentAction: AdjustmentAction | 'NONE' | null = null): Observable<DisputeView> {
    return this.http.post<DisputeView>(`${BASE}/disputes/${id}/resolve`, { outcome, note, adjustmentAction });
  }

  detail(id: string): Observable<DisputeDetail> {
    return this.http.get<DisputeDetail>(`${BASE}/disputes/${id}/detail`);
  }

  /** @param assigneeId nulo devolve sem responsável */
  assign(id: string, assigneeId: string | null, reason: string | null): Observable<DisputeView> {
    return this.http.post<DisputeView>(`${BASE}/disputes/${id}/assign`, { assigneeId, reason });
  }

  take(id: string): Observable<DisputeView> {
    return this.http.post<DisputeView>(`${BASE}/disputes/${id}/take`, null);
  }

  assigneeOptions(id: string): Observable<AssigneeOption[]> {
    return this.http.get<AssigneeOption[]>(`${BASE}/disputes/${id}/assignee-options`);
  }

  redistribute(fromAssigneeId: string, toAssigneeId: string | null, reason: string): Observable<RedistributionResult> {
    return this.http.post<RedistributionResult>(`${BASE}/disputes/redistributions`, { fromAssigneeId, toAssigneeId, reason });
  }

  creditorRequestDraft(id: string): Observable<CreditorRequestDraft> {
    return this.http.get<CreditorRequestDraft>(`${BASE}/disputes/${id}/creditor-request-draft`);
  }

  requestCreditor(id: string, request: CreditorRequestRequest): Observable<CreditorRequestView> {
    return this.http.post<CreditorRequestView>(`${BASE}/disputes/${id}/creditor-requests`, request);
  }

  /** Resposta do credor com os documentos recebidos (multipart: `response` em JSON + `files`). */
  registerCreditorResponse(id: string, request: CreditorResponseRequest, files: File[]): Observable<CreditorResponseView> {
    const body = new FormData();
    body.append('response', new Blob([JSON.stringify(request)], { type: 'application/json' }));
    files.forEach((f) => body.append('files', f, f.name));
    return this.http.post<CreditorResponseView>(`${BASE}/disputes/${id}/creditor-responses`, body);
  }

  debtorNoticeDraft(id: string): Observable<DebtorNoticeDraft> {
    return this.http.get<DebtorNoticeDraft>(`${BASE}/disputes/${id}/debtor-notice-draft`);
  }

  notifyDebtor(id: string, request: DebtorNoticeRequest): Observable<DebtorNoticeView> {
    return this.http.post<DebtorNoticeView>(`${BASE}/disputes/${id}/debtor-notices`, request);
  }

  dismissAdjustment(id: string, note: string): Observable<DisputeView> {
    return this.http.post<DisputeView>(`${BASE}/disputes/${id}/adjustment/dismiss`, { note });
  }

  withdraw(id: string, note: string): Observable<DisputeView> {
    return this.http.post<DisputeView>(`${BASE}/disputes/${id}/withdraw`, { note });
  }

  attachEvidence(id: string, file: File): Observable<EvidenceView> {
    const body = new FormData();
    body.append('file', file, file.name);
    return this.http.post<EvidenceView>(`${BASE}/disputes/${id}/evidences`, body);
  }

  evidences(id: string): Observable<EvidenceView[]> {
    return this.http.get<EvidenceView[]>(`${BASE}/disputes/${id}/evidences`);
  }

  downloadEvidence(id: string, evidence: EvidenceView): Observable<string> {
    return this.files.download(`${BASE}/disputes/${id}/evidences/${evidence.id}`, evidence.fileName);
  }

  // ------------------------------------------------------------ respostas por e-mail

  mailReplies(id: string): Observable<MailReplyView[]> {
    return this.http.get<MailReplyView[]>(`${BASE}/disputes/${id}/mail-replies`);
  }

  confirmMailReply(id: string, replyId: string, position: CreditorPosition, note: string): Observable<CreditorResponseView> {
    return this.http.post<CreditorResponseView>(`${BASE}/disputes/${id}/mail-replies/${replyId}/confirm`, { position, note });
  }

  dismissMailReply(id: string, replyId: string, note: string): Observable<MailReplyView> {
    return this.http.post<MailReplyView>(`${BASE}/disputes/${id}/mail-replies/${replyId}/dismiss`, { note });
  }

  releaseMailReply(id: string, replyId: string, note: string): Observable<MailReplyView> {
    return this.http.post<MailReplyView>(`${BASE}/disputes/${id}/mail-replies/${replyId}/release`, { note });
  }

  mailQuarantine(page: PageRequest): Observable<PageResponse<MailReplyView>> {
    return this.http.get<PageResponse<MailReplyView>>(`${BASE}/dispute-mail-quarantine`, { params: toHttpParams({ page: page.page, size: page.size }) });
  }

  /** Original (.eml) para conferência: download registrado no log de acesso. */
  downloadMailOriginal(id: string, reply: MailReplyView): Observable<string> {
    return this.files.download(`${BASE}/disputes/${id}/mail-replies/${reply.id}/original`, `email-credor-${reply.receivedAt.slice(0, 10)}.eml`);
  }
}

/** Configuração das contestações: parâmetros, prazos por tipo/credor, analistas da distribuição e modelos. */
@Injectable({ providedIn: 'root' })
export class DisputeConfigApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${BASE}/dispute-config`;

  settings(): Observable<DisputeSettingsView> {
    return this.http.get<DisputeSettingsView>(`${this.base}/settings`);
  }

  updateSettings(request: DisputeSettingsRequest): Observable<DisputeSettingsView> {
    return this.http.put<DisputeSettingsView>(`${this.base}/settings`, request);
  }

  rules(): Observable<DeadlineRuleView[]> {
    return this.http.get<DeadlineRuleView[]>(`${this.base}/deadline-rules`);
  }

  createRule(request: { creditorId: string | null; type: string | null; responseDays: number; internalDays: number }): Observable<DeadlineRuleView> {
    return this.http.post<DeadlineRuleView>(`${this.base}/deadline-rules`, request);
  }

  updateRule(id: string, responseDays: number, internalDays: number): Observable<DeadlineRuleView> {
    return this.http.put<DeadlineRuleView>(`${this.base}/deadline-rules/${id}`, { responseDays, internalDays });
  }

  removeRule(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/deadline-rules/${id}`);
  }

  preview(creditorId: string, type: string): Observable<DeadlinePreview> {
    return this.http.get<DeadlinePreview>(`${this.base}/deadline-preview`, { params: { creditorId, type } });
  }

  pool(): Observable<PoolMemberView[]> {
    return this.http.get<PoolMemberView[]>(`${this.base}/pool`);
  }

  addPoolMember(creditorId: string | null, userId: string): Observable<PoolMemberView> {
    return this.http.post<PoolMemberView>(`${this.base}/pool`, { creditorId, userId });
  }

  removePoolMember(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/pool/${id}`);
  }

  templates(): Observable<DisputeTemplateView[]> {
    return this.http.get<DisputeTemplateView[]>(`${this.base}/templates`);
  }

  history(kind: DisputeTemplateKind, key: string): Observable<DisputeTemplateView[]> {
    return this.http.get<DisputeTemplateView[]>(`${this.base}/templates/${kind}/${key}/history`);
  }

  saveTemplate(kind: DisputeTemplateKind, key: string, subject: string, body: string): Observable<DisputeTemplateView> {
    return this.http.put<DisputeTemplateView>(`${this.base}/templates/${kind}/${key}`, { subject, body });
  }

  restoreDefault(kind: DisputeTemplateKind, key: string): Observable<DisputeTemplateView> {
    return this.http.post<DisputeTemplateView>(`${this.base}/templates/${kind}/${key}/restore-default`, null);
  }
}

@Injectable({ providedIn: 'root' })
export class CollectionSettingsApi {
  private readonly http = inject(HttpClient);

  get(): Observable<SettingsView> {
    return this.http.get<SettingsView>(`${BASE}/settings`);
  }

  update(request: SettingsRequest): Observable<SettingsView> {
    return this.http.put<SettingsView>(`${BASE}/settings`, request);
  }
}
