import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, from, map, switchMap } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { silentErrorContext } from '../../../core/http/http-context';
import { toHttpParams } from '../../../core/http/http-params';
import { FileDownloadService, fileNameFrom } from '../../../shared/data/file-download';
import { PageRequest, PageResponse } from '../../../shared/models/page';
import {
  AdjustmentRequest,
  AdjustmentView,
  BalanceView,
  CalculationView,
  CreateStatementRequest,
  ExpenseView,
  ExportFormat,
  ExportView,
  PeriodSuggestion,
  ReceiptDetailView,
  ReceiptFilter,
  ReceiptView,
  RuleSetRequest,
  RuleSetView,
  SendRequest,
  SimulateRequest,
  StatementFilter,
  StatementView,
  TransferRequest,
  TransferView,
} from './settlement.models';
import { sha256Hex } from './settlement.rules';

const BASE = `${API_V1}/settlement`;

/** Regras de comissão da assessoria por contrato de serviço (versões, publicação, simulação). */
@Injectable({ providedIn: 'root' })
export class CommissionRulesApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${BASE}/rule-sets`;

  list(creditorId: string | null, contractId: string | null = null): Observable<RuleSetView[]> {
    return this.http.get<RuleSetView[]>(this.base, { params: toHttpParams({ creditorId, contractId }) });
  }

  get(id: string): Observable<RuleSetView> {
    return this.http.get<RuleSetView>(`${this.base}/${id}`);
  }

  create(request: RuleSetRequest): Observable<RuleSetView> {
    return this.http.post<RuleSetView>(this.base, request, { context: silentErrorContext() });
  }

  update(id: string, request: RuleSetRequest): Observable<RuleSetView> {
    return this.http.put<RuleSetView>(`${this.base}/${id}`, request, { context: silentErrorContext() });
  }

  publish(id: string, effectiveFrom: string): Observable<RuleSetView> {
    return this.http.post<RuleSetView>(`${this.base}/${id}/publish`, { effectiveFrom }, { context: silentErrorContext() });
  }

  discard(id: string): Observable<RuleSetView> {
    return this.http.post<RuleSetView>(`${this.base}/${id}/discard`, null);
  }

  /** Não grava nada; recusas de validação ficam com a tela. */
  simulate(request: SimulateRequest): Observable<CalculationView> {
    return this.http.post<CalculationView>(`${this.base}/simulate`, request, { context: silentErrorContext() });
  }
}

/** Recebimentos e razão de comissão (append-only, com memória de cálculo). */
@Injectable({ providedIn: 'root' })
export class CommissionLedgerApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${BASE}/receipts`;

  search(filter: ReceiptFilter, page: PageRequest): Observable<PageResponse<ReceiptView>> {
    return this.http.get<PageResponse<ReceiptView>>(this.base, { params: toHttpParams({ ...filter, page: page.page, size: page.size }) });
  }

  get(id: string): Observable<ReceiptDetailView> {
    return this.http.get<ReceiptDetailView>(`${this.base}/${id}`);
  }

  ratePending(creditorId: string): Observable<{ rated: number }> {
    return this.http.post<{ rated: number }>(`${this.base}/rate-pending`, null, { params: toHttpParams({ creditorId }) });
  }
}

/** Arquivo baixado com a conferência do SHA-256 (cabeçalho do servidor × conteúdo recebido). */
export interface VerifiedDownload {
  fileName: string;
  sha256: string;
  /** O conteúdo recebido tem o mesmo SHA-256 informado pelo servidor e pelo registro da exportação. */
  verified: boolean;
}

/** Demonstrativos: ciclo, ajustes, custas, exportações e resposta do credor. */
@Injectable({ providedIn: 'root' })
export class StatementsApi {
  private readonly http = inject(HttpClient);
  private readonly files = inject(FileDownloadService);
  private readonly base = `${BASE}/statements`;

  search(filter: StatementFilter, page: PageRequest): Observable<PageResponse<StatementView>> {
    return this.http.get<PageResponse<StatementView>>(this.base, { params: toHttpParams({ ...filter, page: page.page, size: page.size }) });
  }

  suggestPeriod(creditorId: string): Observable<PeriodSuggestion> {
    return this.http.get<PeriodSuggestion>(`${this.base}/period-suggestion`, { params: toHttpParams({ creditorId }), context: silentErrorContext() });
  }

  create(request: CreateStatementRequest): Observable<StatementView> {
    return this.http.post<StatementView>(this.base, request, { context: silentErrorContext() });
  }

  get(id: string): Observable<StatementView> {
    return this.http.get<StatementView>(`${this.base}/${id}`);
  }

  receipts(id: string, page: PageRequest): Observable<PageResponse<ReceiptView>> {
    return this.http.get<PageResponse<ReceiptView>>(`${this.base}/${id}/receipts`, { params: toHttpParams({ page: page.page, size: page.size }) });
  }

  expenses(id: string): Observable<ExpenseView[]> {
    return this.http.get<ExpenseView[]>(`${this.base}/${id}/expenses`);
  }

  /** Ações do ciclo; as recusas de regra (segregação, pendências) ficam com a tela. */
  action(id: string, action: 'calculate' | 'review' | 'approve'): Observable<StatementView> {
    return this.http.post<StatementView>(`${this.base}/${id}/${action}`, null, { context: silentErrorContext() });
  }

  send(id: string, request: SendRequest): Observable<StatementView> {
    return this.http.post<StatementView>(`${this.base}/${id}/send`, request, { context: silentErrorContext() });
  }

  acknowledge(id: string, note: string | null): Observable<StatementView> {
    return this.http.post<StatementView>(`${this.base}/${id}/acknowledge`, { note }, { context: silentErrorContext() });
  }

  contest(id: string, reason: string): Observable<StatementView> {
    return this.http.post<StatementView>(`${this.base}/${id}/contest`, { reason }, { context: silentErrorContext() });
  }

  cancel(id: string, reason: string): Observable<StatementView> {
    return this.http.post<StatementView>(`${this.base}/${id}/cancel`, { reason }, { context: silentErrorContext() });
  }

  adjustments(id: string): Observable<AdjustmentView[]> {
    return this.http.get<AdjustmentView[]>(`${this.base}/${id}/adjustments`);
  }

  requestAdjustment(id: string, request: AdjustmentRequest): Observable<AdjustmentView> {
    return this.http.post<AdjustmentView>(`${this.base}/${id}/adjustments`, request, { context: silentErrorContext() });
  }

  decideAdjustment(adjustmentId: string, decision: 'approve' | 'reject', note: string | null): Observable<AdjustmentView> {
    return this.http.post<AdjustmentView>(`${BASE}/adjustments/${adjustmentId}/${decision}`, { note }, { context: silentErrorContext() });
  }

  exports(id: string): Observable<ExportView[]> {
    return this.http.get<ExportView[]>(`${this.base}/${id}/exports`);
  }

  export(id: string, format: ExportFormat): Observable<ExportView> {
    return this.http.post<ExportView>(`${this.base}/${id}/exports`, { format }, { context: silentErrorContext() });
  }

  /**
   * Baixa o arquivo (o servidor registra `FILE_DOWNLOADED`) e confere o SHA-256 do conteúdo com o
   * cabeçalho `X-Content-SHA256` e com o registrado na exportação antes de salvar.
   */
  download(view: ExportView): Observable<VerifiedDownload> {
    return this.http.get(`${BASE}/exports/${view.id}/content`, { observe: 'response', responseType: 'blob' }).pipe(
      switchMap((response) => {
        const blob = response.body ?? new Blob();
        const header = (response.headers.get('X-Content-SHA256') ?? '').toLowerCase();
        const name = fileNameFrom(response) ?? view.fileName;
        return from(blob.arrayBuffer().then((buffer) => sha256Hex(buffer))).pipe(
          map((actual) => {
            this.files.saveBlob(blob, name);
            const expected = view.sha256.toLowerCase();
            return { fileName: name, sha256: actual, verified: actual === expected && (!header || header === expected) };
          }),
        );
      }),
    );
  }
}

/** Chave de idempotência por intenção do usuário (um envio = uma chave, repetida em novas tentativas). */
export function newIdempotencyKey(): string {
  return globalThis.crypto.randomUUID();
}

/** Repasses ao credor (com comprovante) e saldo a repassar por credor. */
@Injectable({ providedIn: 'root' })
export class TransfersApi {
  private readonly http = inject(HttpClient);
  private readonly files = inject(FileDownloadService);
  private readonly base = `${BASE}/transfers`;

  /** Multipart `transfer` (JSON) + `evidence` (arquivo), com `Idempotency-Key`. */
  register(request: TransferRequest, evidence: File, key: string): Observable<TransferView> {
    const body = new FormData();
    body.append('transfer', new Blob([JSON.stringify(request)], { type: 'application/json' }));
    body.append('evidence', evidence, evidence.name);
    return this.http.post<TransferView>(this.base, body, { headers: new HttpHeaders({ 'Idempotency-Key': key }), context: silentErrorContext() });
  }

  search(creditorId: string | null, page: PageRequest): Observable<PageResponse<TransferView>> {
    return this.http.get<PageResponse<TransferView>>(this.base, { params: toHttpParams({ creditorId, page: page.page, size: page.size }) });
  }

  cancel(id: string, reason: string): Observable<TransferView> {
    return this.http.post<TransferView>(`${this.base}/${id}/cancel`, { reason }, { context: silentErrorContext() });
  }

  downloadEvidence(id: string): Observable<string> {
    return this.files.download(`${this.base}/${id}/evidence`, `comprovante-repasse-${id.slice(0, 8)}`);
  }

  balances(creditorId: string | null): Observable<BalanceView[]> {
    return this.http.get<BalanceView[]>(`${BASE}/balances`, { params: toHttpParams({ creditorId }) });
  }
}
