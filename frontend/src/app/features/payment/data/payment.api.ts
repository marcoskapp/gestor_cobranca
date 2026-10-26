import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { silentErrorContext } from '../../../core/http/http-context';
import { toHttpParams } from '../../../core/http/http-params';
import { FileDownloadService } from '../../../shared/data/file-download';
import { PageRequest, PageResponse } from '../../../shared/models/page';
import {
  CreateInstructionRequest,
  CreatePixAccountRequest,
  CredentialsRequest,
  DivergenceFilter,
  DivergenceView,
  ExternalPaymentRequest,
  InstructionFilter,
  InstructionView,
  PaymentFilter,
  PaymentSettingsRequest,
  PaymentSettingsView,
  PaymentView,
  PixAccountView,
  PixWebhookOptions,
  ReallocateRequest,
  ResolveRequest,
  ReverseRequest,
  StatementFormat,
  StatementImportView,
  StatementLineView,
  UpdatePixAccountRequest,
} from './payment.models';

const BASE = `${API_V1}/payment`;

/** Chave de idempotência por intenção do usuário (um envio = uma chave, repetida em novas tentativas). */
export function newIdempotencyKey(): string {
  return globalThis.crypto.randomUUID();
}

function idempotency(key: string): HttpHeaders {
  return new HttpHeaders({ 'Idempotency-Key': key });
}

/** Instruções de pagamento (Pix imediato/com vencimento; boleto e link aguardam provedor). */
@Injectable({ providedIn: 'root' })
export class InstructionsApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${BASE}/instructions`;

  /** Registro no PSP é assíncrono: nasce `PENDING`; acompanhe por {@link get}. Erros de regra ficam com o diálogo. */
  create(request: CreateInstructionRequest, key: string): Observable<InstructionView> {
    return this.http.post<InstructionView>(this.base, request, { headers: idempotency(key), context: silentErrorContext() });
  }

  search(filter: InstructionFilter, page: PageRequest): Observable<PageResponse<InstructionView>> {
    return this.http.get<PageResponse<InstructionView>>(this.base, { params: toHttpParams({ ...filter, page: page.page, size: page.size }) });
  }

  get(id: string): Observable<InstructionView> {
    return this.http.get<InstructionView>(`${this.base}/${id}`);
  }

  cancel(id: string, reason: string): Observable<InstructionView> {
    return this.http.post<InstructionView>(`${this.base}/${id}/cancel`, { reason });
  }
}

/** Pagamentos: consulta, externo com evidência, revisão, estorno e realocação. */
@Injectable({ providedIn: 'root' })
export class PaymentsApi {
  private readonly http = inject(HttpClient);
  private readonly files = inject(FileDownloadService);
  private readonly base = `${BASE}/payments`;

  search(filter: PaymentFilter, page: PageRequest): Observable<PageResponse<PaymentView>> {
    return this.http.get<PageResponse<PaymentView>>(this.base, { params: toHttpParams({ ...filter, page: page.page, size: page.size }) });
  }

  get(id: string): Observable<PaymentView> {
    return this.http.get<PaymentView>(`${this.base}/${id}`);
  }

  /** Pagamento dono de uma alocação (o `paymentId` das baixas no acordo). */
  byAllocation(allocationId: string): Observable<PaymentView> {
    return this.http.get<PaymentView>(`${this.base}/by-allocation/${allocationId}`);
  }

  /** Multipart `payment` (JSON) + `evidence` (arquivo). Erros de regra ficam com o diálogo. */
  registerExternal(request: ExternalPaymentRequest, evidence: File, key: string): Observable<PaymentView> {
    const body = new FormData();
    body.append('payment', new Blob([JSON.stringify(request)], { type: 'application/json' }));
    body.append('evidence', evidence, evidence.name);
    return this.http.post<PaymentView>(`${this.base}/external`, body, { headers: idempotency(key), context: silentErrorContext() });
  }

  reviewQueue(page: PageRequest): Observable<PageResponse<PaymentView>> {
    return this.http.get<PageResponse<PaymentView>>(`${this.base}/review-queue`, { params: toHttpParams({ page: page.page, size: page.size }) });
  }

  approve(id: string, note: string | null): Observable<PaymentView> {
    return this.http.post<PaymentView>(`${this.base}/${id}/approve`, { note }, { context: silentErrorContext() });
  }

  reject(id: string, note: string): Observable<PaymentView> {
    return this.http.post<PaymentView>(`${this.base}/${id}/reject`, { note }, { context: silentErrorContext() });
  }

  reverse(id: string, request: ReverseRequest): Observable<PaymentView> {
    return this.http.post<PaymentView>(`${this.base}/${id}/reverse`, request, { context: silentErrorContext() });
  }

  pendingReallocation(page: PageRequest): Observable<PageResponse<PaymentView>> {
    return this.http.get<PageResponse<PaymentView>>(`${this.base}/pending-reallocation`, { params: toHttpParams({ page: page.page, size: page.size }) });
  }

  reallocate(id: string, request: ReallocateRequest): Observable<PaymentView> {
    return this.http.post<PaymentView>(`${this.base}/${id}/reallocations`, request, { context: silentErrorContext() });
  }

  /** Comprovante do pagamento externo (sem cache; registra `FILE_DOWNLOADED`). */
  downloadEvidence(id: string): Observable<string> {
    return this.files.download(`${this.base}/${id}/evidence`, `comprovante-${id.slice(0, 8)}`);
  }
}

/** Conciliação: extratos/arquivos de retorno e fila de divergências (`RECONCILIATION_MANAGE`). */
@Injectable({ providedIn: 'root' })
export class ReconciliationApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${BASE}/reconciliation`;

  /** 202: processamento assíncrono. Mesmo arquivo de novo → 409 (`statement.already_imported`). */
  importStatement(file: File, format: StatementFormat, creditorId: string | null): Observable<StatementImportView> {
    const body = new FormData();
    body.append('file', file, file.name);
    return this.http.post<StatementImportView>(`${this.base}/statements`, body, {
      params: toHttpParams({ format, creditorId }),
      context: silentErrorContext(),
    });
  }

  imports(page: PageRequest): Observable<PageResponse<StatementImportView>> {
    return this.http.get<PageResponse<StatementImportView>>(`${this.base}/statements`, { params: toHttpParams({ page: page.page, size: page.size }) });
  }

  getImport(id: string): Observable<StatementImportView> {
    return this.http.get<StatementImportView>(`${this.base}/statements/${id}`);
  }

  lines(id: string, page: PageRequest): Observable<PageResponse<StatementLineView>> {
    return this.http.get<PageResponse<StatementLineView>>(`${this.base}/statements/${id}/lines`, { params: toHttpParams({ page: page.page, size: page.size }) });
  }

  divergences(filter: DivergenceFilter, page: PageRequest): Observable<PageResponse<DivergenceView>> {
    return this.http.get<PageResponse<DivergenceView>>(`${this.base}/divergences`, { params: toHttpParams({ ...filter, page: page.page, size: page.size }) });
  }

  divergence(id: string): Observable<DivergenceView> {
    return this.http.get<DivergenceView>(`${this.base}/divergences/${id}`);
  }

  resolve(id: string, request: ResolveRequest): Observable<DivergenceView> {
    return this.http.post<DivergenceView>(`${this.base}/divergences/${id}/resolve`, request, { context: silentErrorContext() });
  }
}

/** Contas Pix (`INTEGRATION_MANAGE`). Segredos vão só na ida; a API nunca os devolve. */
@Injectable({ providedIn: 'root' })
export class PixAccountsApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${BASE}/pix-accounts`;

  list(): Observable<PixAccountView[]> {
    return this.http.get<PixAccountView[]>(this.base);
  }

  get(id: string): Observable<PixAccountView> {
    return this.http.get<PixAccountView>(`${this.base}/${id}`);
  }

  webhookOptions(): Observable<PixWebhookOptions> {
    return this.http.get<PixWebhookOptions>(`${this.base}/webhook-options`);
  }

  create(request: CreatePixAccountRequest): Observable<PixAccountView> {
    return this.http.post<PixAccountView>(this.base, request, { context: silentErrorContext() });
  }

  update(id: string, request: UpdatePixAccountRequest): Observable<PixAccountView> {
    return this.http.put<PixAccountView>(`${this.base}/${id}`, request, { context: silentErrorContext() });
  }

  rotateCredentials(id: string, request: CredentialsRequest): Observable<PixAccountView> {
    return this.http.put<PixAccountView>(`${this.base}/${id}/credentials`, request, { context: silentErrorContext() });
  }

  registerWebhook(id: string): Observable<PixAccountView> {
    return this.http.post<PixAccountView>(`${this.base}/${id}/webhook`, null);
  }

  activate(id: string): Observable<PixAccountView> {
    return this.http.post<PixAccountView>(`${this.base}/${id}/activate`, null);
  }

  deactivate(id: string): Observable<PixAccountView> {
    return this.http.post<PixAccountView>(`${this.base}/${id}/deactivate`, null);
  }
}

/** Parâmetros de pagamentos da assessoria (leitura `PAYMENT_VIEW`; alteração `ORG_SETTINGS_MANAGE`). */
@Injectable({ providedIn: 'root' })
export class PaymentSettingsApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${BASE}/settings`;

  get(): Observable<PaymentSettingsView> {
    return this.http.get<PaymentSettingsView>(this.base);
  }

  update(request: PaymentSettingsRequest): Observable<PaymentSettingsView> {
    return this.http.put<PaymentSettingsView>(this.base, request, { context: silentErrorContext() });
  }
}
