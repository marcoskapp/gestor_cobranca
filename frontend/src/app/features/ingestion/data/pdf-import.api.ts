import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map, switchMap } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { silentErrorContext } from '../../../core/http/http-context';
import { toHttpParams } from '../../../core/http/http-params';
import { FileDownloadService } from '../../../shared/data/file-download';
import { PageRequest, PageResponse } from '../../../shared/models/page';
import { DecisionTarget, ExtractionMap, PdfImportStatus, PdfImportSummaryView, PdfImportView, PdfRecordView, PdfTemplateView, RecordDecision, RecordDecisionResult, RecordFacets, RecordFilter } from './pdf-import.models';

const BASE = `${API_V1}/ingestion/pdf-imports`;

/** Importação PDF inteligente: envio, mapa de extração, títulos, imagens das páginas e aprovação. */
@Injectable({ providedIn: 'root' })
export class PdfImportsApi {
  private readonly http = inject(HttpClient);
  private readonly files = inject(FileDownloadService);

  /** Envio do PDF (202). Erros de regra voltam sem notificação genérica: a tela os apresenta. */
  upload(file: File, portfolioId: string): Observable<PdfImportView> {
    const body = new FormData();
    body.append('file', file, file.name);
    return this.http.post<PdfImportView>(BASE, body, { params: toHttpParams({ portfolioId }), context: silentErrorContext() });
  }

  search(filter: { creditorId?: string | null; portfolioId?: string | null; status?: PdfImportStatus[] }, page: PageRequest): Observable<PageResponse<PdfImportSummaryView>> {
    return this.http.get<PageResponse<PdfImportSummaryView>>(BASE, {
      params: toHttpParams({ creditorId: filter.creditorId, portfolioId: filter.portfolioId, status: filter.status?.join(','), page: page.page, size: page.size }),
    });
  }

  get(id: string): Observable<PdfImportView> {
    return this.http.get<PdfImportView>(`${BASE}/${id}`);
  }

  records(id: string, filter: RecordFilter, page: PageRequest): Observable<PageResponse<PdfRecordView>> {
    return this.http.get<PageResponse<PdfRecordView>>(`${BASE}/${id}/records`, {
      params: toHttpParams({ status: filter.status, debtor: filter.debtor, q: filter.q || null, page: page.page, size: page.size }),
    });
  }

  /** Contadores por situação (com o devedor e o texto do filtro) e os devedores. */
  facets(id: string, filter: RecordFilter): Observable<RecordFacets> {
    return this.http.get<RecordFacets>(`${BASE}/${id}/records/facets`, { params: toHttpParams({ debtor: filter.debtor, q: filter.q || null }) });
  }

  /**
   * Imagem da página como `data:` URL (a CSP só aceita imagens da própria origem ou `data:`); o token segue no
   * cabeçalho, nunca na URL. O acesso fica no log de acesso.
   */
  pageImage(id: string, page: number): Observable<string> {
    return this.http.get(`${BASE}/${id}/pages/${page}`, { responseType: 'blob' }).pipe(switchMap((blob) => blobToDataUrl(blob)));
  }

  /** PDF original (dados pessoais em claro: exige `DEBTOR_SENSITIVE_VIEW`; registra `FILE_DOWNLOADED`). */
  downloadOriginal(id: string, name: string): Observable<string> {
    return this.files.download(`${BASE}/${id}/file`, name);
  }

  updateMap(id: string, mapValue: ExtractionMap, revision: number): Observable<PdfImportView> {
    return this.http.put<PdfImportView>(`${BASE}/${id}/map`, { map: mapValue, revision }, { context: silentErrorContext() });
  }

  requestNewMap(id: string): Observable<PdfImportView> {
    return this.http.post<PdfImportView>(`${BASE}/${id}/new-map`, {});
  }

  /**
   * Exclui, reinclui ou confirma títulos — um, vários pelos números ou todos os do filtro — numa operação. O servidor
   * aplica o permitido e devolve o que ficou de fora (ex.: confiança baixa só é confirmada um a um).
   */
  decide(id: string, target: DecisionTarget, decision: RecordDecision, justification: string | null, revision: number): Observable<RecordDecisionResult> {
    const body =
      'ordinals' in target
        ? { ordinals: target.ordinals, decision, justification, revision }
        : { filter: { status: target.filter.status, debtor: target.filter.debtor, q: target.filter.q || null }, expectedCount: target.expectedCount, decision, justification, revision };
    return this.http.post<RecordDecisionResult>(`${BASE}/${id}/records/decisions`, body, { context: silentErrorContext() });
  }

  approve(id: string, revision: number, totalsJustification: string | null, visualReadingConfirmed: boolean): Observable<PdfImportView> {
    return this.http.post<PdfImportView>(`${BASE}/${id}/approve`, { revision, totalsJustification, visualReadingConfirmed }, { context: silentErrorContext() });
  }

  saveTemplate(id: string, name: string, revision: number): Observable<PdfTemplateView> {
    return this.http.post<PdfTemplateView>(`${BASE}/${id}/template`, { name, revision });
  }

  discard(id: string, reason: string): Observable<PdfImportView> {
    return this.http.post<PdfImportView>(`${BASE}/${id}/discard`, { reason });
  }

  templates(creditorId: string, includeInactive = false): Observable<PdfTemplateView[]> {
    return this.http.get<PdfTemplateView[]>(`${BASE}/templates`, { params: toHttpParams({ creditorId, includeInactive }) });
  }

  deactivateTemplate(templateId: string): Observable<PdfTemplateView> {
    return this.http.post<PdfTemplateView>(`${BASE}/templates/${templateId}/deactivate`, {});
  }
}

function blobToDataUrl(blob: Blob): Observable<string> {
  return new Observable<string>((subscriber) => {
    const reader = new FileReader();
    reader.onload = () => {
      subscriber.next(String(reader.result));
      subscriber.complete();
    };
    reader.onerror = () => subscriber.error(reader.error);
    reader.readAsDataURL(blob);
    return () => reader.abort();
  }).pipe(map((url) => url));
}
