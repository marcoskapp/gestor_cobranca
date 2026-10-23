import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../config/api';
import { silentErrorContext } from '../http/http-context';
import { UiState, UiStatePatch } from './ui-state.models';

/**
 * Estado de interface do usuário autenticado. Falhas são silenciosas: preferências e onboarding
 * nunca interrompem o trabalho com mensagens de erro.
 */
@Injectable({ providedIn: 'root' })
export class UiStateApi {
  private readonly http = inject(HttpClient);
  private readonly url = `${API_V1}/me/ui-state`;

  get(): Observable<UiState> {
    return this.http.get<UiState>(this.url, { context: silentErrorContext() });
  }

  update(patch: UiStatePatch): Observable<UiState> {
    return this.http.patch<UiState>(this.url, patch, { context: silentErrorContext() });
  }
}
