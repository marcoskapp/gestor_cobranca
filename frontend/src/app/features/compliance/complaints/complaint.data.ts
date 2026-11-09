import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { toHttpParams } from '../../../core/http/http-params';
import { FileDownloadService } from '../../../shared/data/file-download';
import { PageRequest, PageResponse } from '../../../shared/models/page';

/** Reclamações de canais externos (docs/tecnico/desenho/conformidade.md §3). */
export type ComplaintChannel = 'PROCON' | 'CONSUMIDOR_GOV' | 'BACEN' | 'CREDITOR_OMBUDSMAN' | 'RECLAME_AQUI' | 'OTHER';
export type ComplaintSubject =
  | 'ABUSIVE_COLLECTION'
  | 'CONTACT_FREQUENCY'
  | 'THIRD_PARTY_CONTACT'
  | 'DEBT_NOT_RECOGNIZED'
  | 'WRONG_AMOUNT'
  | 'AGREEMENT_OR_PAYMENT'
  | 'CREDIT_BUREAU'
  | 'DATA_PRIVACY'
  | 'OTHER';
export type ComplaintStatus = 'RECEIVED' | 'IN_PROGRESS' | 'ANSWERED' | 'CLOSED';
export type ComplaintOutcome = 'RESOLVED' | 'NOT_ACCEPTED' | 'UNFOUNDED' | 'CLOSED_BY_CHANNEL';
export type DeadlineState = 'ON_TIME' | 'DUE_SOON' | 'OVERDUE' | 'ANSWERED_ON_TIME' | 'ANSWERED_LATE';

export interface ComplaintView {
  id: string;
  creditorId: string;
  debtorId: string | null;
  caseId: string | null;
  channel: ComplaintChannel;
  externalProtocol: string | null;
  subject: ComplaintSubject;
  status: ComplaintStatus;
  receivedAt: string;
  dueDate: string;
  deadlineSource: 'INFORMED' | 'CHANNEL_RULE' | 'DEFAULT';
  deadlineState: DeadlineState;
  daysToDue: number | null;
  assigneeId: string | null;
  assigneeName: string | null;
  answeredAt: string | null;
  answeredOnTime: boolean | null;
  outcome: ComplaintOutcome | null;
  closedAt: string | null;
  evidenceCount: number;
  version: number;
}

export interface ComplaintDetail {
  complaint: ComplaintView;
  summary: string;
  response: string | null;
  registeredBy: string | null;
  registeredByName: string | null;
  answeredBy: string | null;
  answeredByName: string | null;
}

export interface RegisterComplaintRequest {
  creditorId: string;
  debtorId: string | null;
  caseId: string | null;
  channel: ComplaintChannel;
  externalProtocol: string | null;
  subject: ComplaintSubject;
  summary: string;
  receivedAt: string | null;
  responseDueDate: string | null;
}

export interface ComplaintFilter {
  creditorId?: string | null;
  status?: string | null;
  channel?: string | null;
  debtorId?: string | null;
  caseId?: string | null;
  assignee?: string | null;
  unassigned?: boolean;
  deadline?: string | null;
  protocol?: string | null;
}

export interface EvidenceView {
  id: string;
  complaintId: string;
  fileName: string;
  contentType: string;
  size: number;
  sha256: string;
  status: 'PENDING_SCAN' | 'AVAILABLE' | 'REJECTED' | 'FAILED';
  verdict: 'CLEAN' | 'NOT_SCANNED' | 'INFECTED' | null;
  createdAt: string;
  createdBy: string | null;
  createdByName: string | null;
}

export interface DeadlineRuleView {
  channel: ComplaintChannel;
  responseDays: number;
  warningDays: number;
  defaultRule: boolean;
}

export interface AssigneeOption {
  userId: string;
  name: string;
  openComplaints: number;
}

export interface CreditorIndicator {
  creditorId: string;
  received: number;
  answeredOnTime: number;
  answeredLate: number;
  openOverdue: number;
  open: number;
}

export const CHANNEL_LABELS: Record<ComplaintChannel, string> = {
  PROCON: 'Procon',
  CONSUMIDOR_GOV: 'consumidor.gov.br',
  BACEN: 'Banco Central',
  CREDITOR_OMBUDSMAN: 'Ouvidoria do credor',
  RECLAME_AQUI: 'Reclame Aqui',
  OTHER: 'Outro canal',
};

export const SUBJECT_LABELS: Record<ComplaintSubject, string> = {
  ABUSIVE_COLLECTION: 'Cobrança abusiva ou constrangimento',
  CONTACT_FREQUENCY: 'Excesso de contatos ou horário',
  THIRD_PARTY_CONTACT: 'Contato com terceiros',
  DEBT_NOT_RECOGNIZED: 'Não reconhece a dívida',
  WRONG_AMOUNT: 'Valor ou encargos',
  AGREEMENT_OR_PAYMENT: 'Acordo, boleto ou baixa',
  CREDIT_BUREAU: 'Negativação ou protesto',
  DATA_PRIVACY: 'Dados pessoais (LGPD)',
  OTHER: 'Outro assunto',
};

export const STATUS_LABELS: Record<ComplaintStatus, string> = {
  RECEIVED: 'Recebida',
  IN_PROGRESS: 'Em tratamento',
  ANSWERED: 'Respondida',
  CLOSED: 'Encerrada',
};

export const OUTCOME_LABELS: Record<ComplaintOutcome, string> = {
  RESOLVED: 'Atendida',
  NOT_ACCEPTED: 'Respondida sem acolher',
  UNFOUNDED: 'Improcedente',
  CLOSED_BY_CHANNEL: 'Encerrada pelo canal',
};

export const DEADLINE_SOURCE_LABELS: Record<ComplaintView['deadlineSource'], string> = {
  INFORMED: 'informado pelo canal',
  CHANNEL_RULE: 'regra do canal',
  DEFAULT: 'padrão do sistema',
};

/** Texto do prazo na fila: dias até o prazo, vencida ou como foi respondida. */
export function deadlineText(c: Pick<ComplaintView, 'deadlineState' | 'daysToDue'>): string {
  switch (c.deadlineState) {
    case 'ANSWERED_ON_TIME':
      return 'Respondida no prazo';
    case 'ANSWERED_LATE':
      return 'Respondida fora do prazo';
    case 'OVERDUE': {
      const late = Math.max(-(c.daysToDue ?? 0), 1);
      return late === 1 ? 'Vencida há 1 dia' : `Vencida há ${late} dias`;
    }
    default: {
      const days = c.daysToDue ?? 0;
      return days === 0 ? 'Vence hoje' : days === 1 ? 'Vence amanhã' : `Faltam ${days} dias`;
    }
  }
}

export function deadlineTone(state: DeadlineState): 'late' | 'soon' | 'ok' {
  return state === 'OVERDUE' || state === 'ANSWERED_LATE' ? 'late' : state === 'DUE_SOON' ? 'soon' : 'ok';
}

/** Taxa de resposta no prazo (respondidas no prazo ÷ respondidas no período); nula sem respostas. */
export function onTimeRate(i: CreditorIndicator): number | null {
  const answered = i.answeredOnTime + i.answeredLate;
  return answered === 0 ? null : i.answeredOnTime / answered;
}

const BASE = `${API_V1}/complaints`;

@Injectable({ providedIn: 'root' })
export class ComplaintsApi {
  private readonly http = inject(HttpClient);
  private readonly files = inject(FileDownloadService);

  search(filter: ComplaintFilter, page: PageRequest): Observable<PageResponse<ComplaintView>> {
    return this.http.get<PageResponse<ComplaintView>>(BASE, {
      params: toHttpParams({ ...filter, page: page.page, size: page.size }),
    });
  }

  register(request: RegisterComplaintRequest): Observable<ComplaintView> {
    return this.http.post<ComplaintView>(BASE, request);
  }

  detail(id: string): Observable<ComplaintDetail> {
    return this.http.get<ComplaintDetail>(`${BASE}/${id}`);
  }

  reclassify(
    id: string,
    body: { debtorId: string | null; caseId: string | null; externalProtocol: string | null; subject: ComplaintSubject },
  ): Observable<ComplaintView> {
    return this.http.put<ComplaintView>(`${BASE}/${id}`, body);
  }

  changeDueDate(id: string, dueDate: string, reason: string): Observable<ComplaintView> {
    return this.http.put<ComplaintView>(`${BASE}/${id}/due-date`, { dueDate, reason });
  }

  assign(id: string, assigneeId: string | null): Observable<ComplaintView> {
    return this.http.post<ComplaintView>(`${BASE}/${id}/assign`, { assigneeId });
  }

  assigneeOptions(id: string): Observable<AssigneeOption[]> {
    return this.http.get<AssigneeOption[]>(`${BASE}/${id}/assignee-options`);
  }

  start(id: string): Observable<ComplaintView> {
    return this.http.post<ComplaintView>(`${BASE}/${id}/start`, {});
  }

  answer(id: string, response: string, answeredAt: string | null): Observable<ComplaintView> {
    return this.http.post<ComplaintView>(`${BASE}/${id}/answer`, { response, answeredAt });
  }

  close(id: string, outcome: ComplaintOutcome): Observable<ComplaintView> {
    return this.http.post<ComplaintView>(`${BASE}/${id}/close`, { outcome });
  }

  evidences(id: string): Observable<EvidenceView[]> {
    return this.http.get<EvidenceView[]>(`${BASE}/${id}/evidences`);
  }

  attach(id: string, file: File): Observable<EvidenceView> {
    const body = new FormData();
    body.append('file', file, file.name);
    return this.http.post<EvidenceView>(`${BASE}/${id}/evidences`, body);
  }

  download(id: string, evidence: EvidenceView): Observable<string> {
    return this.files.download(`${BASE}/${id}/evidences/${evidence.id}`, evidence.fileName);
  }

  rules(): Observable<DeadlineRuleView[]> {
    return this.http.get<DeadlineRuleView[]>(`${BASE}/deadline-rules`);
  }

  updateRule(channel: ComplaintChannel, responseDays: number, warningDays: number): Observable<DeadlineRuleView> {
    return this.http.put<DeadlineRuleView>(`${BASE}/deadline-rules/${channel}`, { responseDays, warningDays });
  }

  indicator(from: string | null, to: string | null): Observable<CreditorIndicator[]> {
    return this.http.get<CreditorIndicator[]>(`${BASE}/indicators/by-creditor`, { params: toHttpParams({ from, to }) });
  }
}
