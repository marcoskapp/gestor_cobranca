import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, from, map, switchMap } from 'rxjs';
import { silentErrorContext } from '../../core/http/http-context';
import { FileDownloadService, fileNameFrom } from '../../shared/data/file-download';
import { sha256Hex } from '../../shared/util/hash';

/**
 * Download do portal do credor com conferência do código de integridade: baixa (o servidor registra o download),
 * calcula o SHA-256 do conteúdo e só salva se conferir com o mostrado na tela e com o cabeçalho `X-Content-SHA256`.
 */
@Injectable({ providedIn: 'root' })
export class CreditorVerifiedDownload {
  private readonly http = inject(HttpClient);
  private readonly files = inject(FileDownloadService);

  fetch(url: string, expectedSha256: string, fallbackName: string): Observable<{ verified: boolean }> {
    return this.http.get(url, { observe: 'response', responseType: 'blob', context: silentErrorContext() }).pipe(
      switchMap((response) => {
        const blob = response.body ?? new Blob();
        const name = fileNameFrom(response) ?? fallbackName;
        const header = (response.headers.get('X-Content-SHA256') ?? '').toLowerCase();
        const expected = expectedSha256.toLowerCase();
        return from(blob.arrayBuffer().then((buffer) => sha256Hex(buffer))).pipe(
          map((actual) => {
            const verified = actual === expected && (!header || header === expected);
            if (verified) {
              this.files.saveBlob(blob, name);
            }
            return { verified };
          }),
        );
      }),
    );
  }
}
