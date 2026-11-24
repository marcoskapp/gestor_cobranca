import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { silentErrorContext } from '../../core/http/http-context';
import { CREDITOR_API } from './creditor.api';
import { CreditorVerifiedDownload } from './creditor-download';
import { CreditorDisputeDetail, CreditorDisputeDocument, CreditorDisputeItem, CreditorDisputePosition } from './creditor.models';

/** Contestações no portal do credor (`/api/creditor/v1/disputes`). O credor vem sempre da sessão. */
@Injectable({ providedIn: 'root' })
export class CreditorDisputesApi {
  private readonly http = inject(HttpClient);
  private readonly downloads = inject(CreditorVerifiedDownload);
  private readonly base = `${CREDITOR_API}/disputes`;

  list(): Observable<CreditorDisputeItem[]> {
    return this.http.get<CreditorDisputeItem[]>(this.base, { context: silentErrorContext() });
  }

  get(id: string): Observable<CreditorDisputeDetail> {
    return this.http.get<CreditorDisputeDetail>(`${this.base}/${encodeURIComponent(id)}`, { context: silentErrorContext() });
  }

  /** Resposta ao pedido: posição, resumo e até 5 documentos (passam pela verificação de segurança da assessoria). */
  respond(id: string, requestId: string, position: CreditorDisputePosition, note: string, files: readonly File[]): Observable<CreditorDisputeDetail> {
    const body = new FormData();
    body.append('response', new Blob([JSON.stringify({ requestId, position, note })], { type: 'application/json' }));
    files.forEach((f) => body.append('files', f, f.name));
    return this.http.post<CreditorDisputeDetail>(`${this.base}/${encodeURIComponent(id)}/responses`, body,
      { context: silentErrorContext() });
  }

  download(disputeId: string, doc: CreditorDisputeDocument): Observable<{ verified: boolean }> {
    return this.downloads.fetch(`${this.base}/${encodeURIComponent(disputeId)}/documents/${encodeURIComponent(doc.id)}/content`,
      doc.sha256, doc.fileName);
  }
}
