import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { silentErrorContext } from '../../../core/http/http-context';
import { toHttpParams } from '../../../core/http/http-params';
import { FileDownloadService } from '../../../shared/data/file-download';
import { PageRequest, PageResponse } from '../../../shared/models/page';
import {
  AlertDecisionRequest,
  AlertStatus,
  AlertType,
  AlertView,
  AnalysisView,
  BatchFilter,
  BreakdownRow,
  CanonicalLayoutView,
  FormatOption,
  ImportBatchView,
  ImportIssueView,
  ImportLayoutView,
  ImportMode,
  IssueSeverity,
  PublishLayoutRequest,
  ReviewStatus,
  ReviewSummaryView,
  ReviewView,
  TotalsCheckView,
  TotalsRequest,
  UploadRequest,
} from './ingestion.models';

const BASE = `${API_V1}/ingestion`;

/** Lotes de importação de carteira. O processamento é assíncrono: acompanhe pelo status do lote. */
@Injectable({ providedIn: 'root' })
export class ImportBatchesApi {
  private readonly http = inject(HttpClient);
  private readonly files = inject(FileDownloadService);

  /**
   * Envio de CSV/XLSX (202). Erros de regra (arquivo repetido, formato) voltam sem notificação
   * genérica: o diálogo de envio os apresenta e oferece a confirmação quando cabe.
   */
  upload(file: File, request: UploadRequest): Observable<ImportBatchView> {
    const body = new FormData();
    body.append('file', file, file.name);
    return this.http.post<ImportBatchView>(`${BASE}/batches`, body, {
      params: toHttpParams({ portfolioId: request.portfolioId, layoutId: request.layoutId, mode: request.mode, force: request.force, reason: request.reason }),
      context: silentErrorContext(),
    });
  }

  search(filter: BatchFilter, page: PageRequest): Observable<PageResponse<ImportBatchView>> {
    return this.http.get<PageResponse<ImportBatchView>>(`${BASE}/batches`, { params: toHttpParams({ ...filter, page: page.page, size: page.size }) });
  }

  get(id: string): Observable<ImportBatchView> {
    return this.http.get<ImportBatchView>(`${BASE}/batches/${id}`);
  }

  issues(id: string, severity: IssueSeverity | null, page: PageRequest): Observable<PageResponse<ImportIssueView>> {
    return this.http.get<PageResponse<ImportIssueView>>(`${BASE}/batches/${id}/issues`, {
      params: toHttpParams({ severity, page: page.page, size: page.size }),
    });
  }

  /** Relatório CSV de ocorrências (valores mascarados; registra `DATA_EXPORTED`). */
  downloadIssues(id: string): Observable<string> {
    return this.files.download(`${BASE}/batches/${id}/issues/report`, `ocorrencias-importacao-${id}.csv`);
  }

  /** Arquivo original com dados pessoais em claro (exige `DEBTOR_SENSITIVE_VIEW`; registra `FILE_DOWNLOADED`). */
  downloadOriginal(id: string, name: string): Observable<string> {
    return this.files.download(`${BASE}/batches/${id}/file`, name);
  }

  cancel(id: string, reason: string): Observable<ImportBatchView> {
    return this.http.post<ImportBatchView>(`${BASE}/batches/${id}/cancel`, { reason });
  }

  reprocess(id: string, mode: ImportMode, reason: string | null): Observable<ImportBatchView> {
    return this.http.post<ImportBatchView>(`${BASE}/batches/${id}/reprocess`, { mode, reason }, { context: silentErrorContext() });
  }
}

/** Layouts de importação por credor e o layout padrão. */
@Injectable({ providedIn: 'root' })
export class ImportLayoutsApi {
  private readonly http = inject(HttpClient);
  private readonly files = inject(FileDownloadService);

  list(creditorId: string | null, includeInactive: boolean): Observable<ImportLayoutView[]> {
    return this.http.get<ImportLayoutView[]>(`${BASE}/layouts`, { params: toHttpParams({ creditorId, includeInactive }) });
  }

  standard(): Observable<CanonicalLayoutView> {
    return this.http.get<CanonicalLayoutView>(`${BASE}/layouts/standard`);
  }

  downloadTemplate(format: 'csv' | 'xlsx'): Observable<string> {
    return this.files.download(`${BASE}/layouts/standard/template?format=${format}`, `modelo-importacao.${format}`);
  }

  publish(request: PublishLayoutRequest): Observable<ImportLayoutView> {
    return this.http.post<ImportLayoutView>(`${BASE}/layouts`, request);
  }

  deactivate(id: string): Observable<ImportLayoutView> {
    return this.http.post<ImportLayoutView>(`${BASE}/layouts/${id}/deactivate`, null);
  }
}

/**
 * Conferência da importação (assistente em passos). Ações com regra de negócio (formato, totais, efetivação) voltam sem
 * notificação genérica: a tela apresenta o motivo no passo.
 */
@Injectable({ providedIn: 'root' })
export class ImportReviewsApi {
  private readonly http = inject(HttpClient);

  start(file: File, portfolioId: string, layoutId: string | null): Observable<ReviewView> {
    const body = new FormData();
    body.append('file', file, file.name);
    return this.http.post<ReviewView>(`${BASE}/reviews`, body, { params: toHttpParams({ portfolioId, layoutId }), context: silentErrorContext() });
  }

  startFromBatch(batchId: string): Observable<ReviewView> {
    return this.http.post<ReviewView>(`${BASE}/reviews/from-batch`, { batchId }, { context: silentErrorContext() });
  }

  search(filter: { status?: ReviewStatus[]; creditorId?: string | null; portfolioId?: string | null }, page: PageRequest): Observable<PageResponse<ReviewSummaryView>> {
    return this.http.get<PageResponse<ReviewSummaryView>>(`${BASE}/reviews`, {
      params: toHttpParams({ status: filter.status?.join(',') ?? null, creditorId: filter.creditorId, portfolioId: filter.portfolioId, page: page.page, size: page.size }),
    });
  }

  get(id: string): Observable<ReviewView> {
    return this.http.get<ReviewView>(`${BASE}/reviews/${id}`);
  }

  /** Salva o passo atual (estado do assistente no servidor). */
  step(id: string, step: number): Observable<ReviewView> {
    return this.http.put<ReviewView>(`${BASE}/reviews/${id}/step`, { step }, { context: silentErrorContext() });
  }

  confirmFormat(id: string, batchId: string, decisions: Record<string, FormatOption>): Observable<ReviewView> {
    return this.http.post<ReviewView>(`${BASE}/reviews/${id}/format`, { batchId, decisions }, { context: silentErrorContext() });
  }

  checkTotals(id: string, request: TotalsRequest): Observable<TotalsCheckView> {
    return this.http.post<TotalsCheckView>(`${BASE}/reviews/${id}/totals`, request, { context: silentErrorContext() });
  }

  breakdown(id: string): Observable<BreakdownRow[]> {
    return this.http.get<BreakdownRow[]>(`${BASE}/reviews/${id}/totals/breakdown`, { context: silentErrorContext() });
  }

  alerts(id: string, type: AlertType | null, status: AlertStatus | null, page: PageRequest): Observable<PageResponse<AlertView>> {
    return this.http.get<PageResponse<AlertView>>(`${BASE}/reviews/${id}/alerts`, { params: toHttpParams({ type, status, page: page.page, size: page.size }) });
  }

  decide(id: string, request: AlertDecisionRequest): Observable<AnalysisView> {
    return this.http.post<AnalysisView>(`${BASE}/reviews/${id}/alerts/decisions`, request, { context: silentErrorContext() });
  }

  commit(id: string, confirmationText: string, forceReason: string | null): Observable<ReviewView> {
    return this.http.post<ReviewView>(`${BASE}/reviews/${id}/commit`, { confirmationText, forceReason }, { context: silentErrorContext() });
  }

  approve(id: string, confirmationText: string, note: string | null): Observable<ReviewView> {
    return this.http.post<ReviewView>(`${BASE}/reviews/${id}/approve`, { confirmationText, note }, { context: silentErrorContext() });
  }

  reject(id: string, reason: string): Observable<ReviewView> {
    return this.http.post<ReviewView>(`${BASE}/reviews/${id}/reject`, { reason });
  }

  cancel(id: string, reason: string): Observable<ReviewView> {
    return this.http.post<ReviewView>(`${BASE}/reviews/${id}/cancel`, { reason });
  }
}
