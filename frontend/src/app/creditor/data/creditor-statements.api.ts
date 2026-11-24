import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { silentErrorContext } from '../../core/http/http-context';
import { CREDITOR_API } from './creditor.api';
import { CreditorVerifiedDownload } from './creditor-download';
import { CreditorOpenPeriod, CreditorStatementDetail, CreditorStatementFile, CreditorStatementItem } from './creditor.models';

/** Prestação de contas no portal do credor (`/api/creditor/v1/statements`). O credor vem sempre da sessão. */
@Injectable({ providedIn: 'root' })
export class CreditorStatementsApi {
  private readonly http = inject(HttpClient);
  private readonly downloads = inject(CreditorVerifiedDownload);
  private readonly base = `${CREDITOR_API}/statements`;

  list(): Observable<CreditorStatementItem[]> {
    return this.http.get<CreditorStatementItem[]>(this.base, { context: silentErrorContext() });
  }

  openPeriod(): Observable<CreditorOpenPeriod> {
    return this.http.get<CreditorOpenPeriod>(`${this.base}/open-period`, { context: silentErrorContext() });
  }

  get(id: string): Observable<CreditorStatementDetail> {
    return this.http.get<CreditorStatementDetail>(`${this.base}/${encodeURIComponent(id)}`, { context: silentErrorContext() });
  }

  /** Aceite com o código do autenticador e os SHA-256 dos arquivos que a pessoa viu (se mudaram, o servidor recusa). */
  acknowledge(id: string, code: string, note: string | null, fileSha256: readonly string[]): Observable<CreditorStatementDetail> {
    return this.http.post<CreditorStatementDetail>(`${this.base}/${encodeURIComponent(id)}/acknowledge`,
      { code, note, fileSha256 }, { context: silentErrorContext() });
  }

  contest(id: string, reason: string): Observable<CreditorStatementDetail> {
    return this.http.post<CreditorStatementDetail>(`${this.base}/${encodeURIComponent(id)}/contest`, { reason },
      { context: silentErrorContext() });
  }

  /** Baixa (o servidor registra o download) e confere o SHA-256 do conteúdo; só salva se conferir. */
  download(statementId: string, file: CreditorStatementFile): Observable<{ verified: boolean }> {
    return this.downloads.fetch(`${this.base}/${encodeURIComponent(statementId)}/files/${encodeURIComponent(file.id)}/content`,
      file.sha256, file.fileName);
  }
}
