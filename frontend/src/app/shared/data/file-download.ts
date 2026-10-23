import { HttpClient, HttpResponse } from '@angular/common/http';
import { DOCUMENT, Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

/**
 * Download de arquivos protegidos da API (evidências, dados do titular, dossiês). A requisição
 * passa pelos interceptors (token em memória); o conteúdo vira um `blob:` temporário, revogado
 * logo após o clique, e não é mantido em cache nem em armazenamento do navegador.
 */
@Injectable({ providedIn: 'root' })
export class FileDownloadService {
  private readonly http = inject(HttpClient);
  private readonly document = inject(DOCUMENT);

  download(url: string, fallbackName: string): Observable<string> {
    return this.http.get(url, { observe: 'response', responseType: 'blob' }).pipe(
      map((response) => {
        const name = fileNameFrom(response) ?? fallbackName;
        this.save(response.body ?? new Blob(), name);
        return name;
      }),
    );
  }

  private save(blob: Blob, name: string): void {
    const view = this.document.defaultView;
    if (!view?.URL?.createObjectURL) {
      return;
    }
    const href = view.URL.createObjectURL(blob);
    const anchor = this.document.createElement('a');
    anchor.href = href;
    anchor.download = name;
    anchor.rel = 'noopener';
    anchor.style.display = 'none';
    this.document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    view.setTimeout(() => view.URL.revokeObjectURL(href), 0);
  }
}

/** Nome do arquivo em `Content-Disposition` (`filename*=UTF-8''…` tem precedência). */
export function fileNameFrom(response: HttpResponse<unknown>): string | null {
  const header = response.headers.get('Content-Disposition');
  if (!header) {
    return null;
  }
  const extended = /filename\*\s*=\s*UTF-8''([^;]+)/i.exec(header);
  if (extended) {
    try {
      return decodeURIComponent(extended[1].trim());
    } catch {
      // nome mal codificado: tenta o parâmetro simples
    }
  }
  const plain = /filename\s*=\s*"?([^";]+)"?/i.exec(header);
  return plain ? plain[1].trim() : null;
}
