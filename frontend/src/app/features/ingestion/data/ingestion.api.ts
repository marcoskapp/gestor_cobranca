import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { silentErrorContext } from '../../../core/http/http-context';
import { toHttpParams } from '../../../core/http/http-params';
import { FileDownloadService } from '../../../shared/data/file-download';
import { PageRequest, PageResponse } from '../../../shared/models/page';
import {
  BatchFilter,
  CanonicalLayoutView,
  ImportBatchView,
  ImportIssueView,
  ImportLayoutView,
  ImportMode,
  IssueSeverity,
  PublishLayoutRequest,
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
