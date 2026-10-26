import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../config/api';
import { silentErrorContext } from '../http/http-context';
import { PendingWorkSummary } from './pending-work.models';

/**
 * Pendências do usuário da sessão. Falhas são silenciosas (a consulta é periódica): o indicador e a
 * tela inicial mostram o próprio estado de erro, sem notificação a cada tentativa.
 */
@Injectable({ providedIn: 'root' })
export class PendingWorkApi {
  private readonly http = inject(HttpClient);

  mine(): Observable<PendingWorkSummary> {
    return this.http.get<PendingWorkSummary>(`${API_V1}/me/pending-work`, { context: silentErrorContext() });
  }
}
