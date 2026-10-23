import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map, of } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { silentErrorContext } from '../../../core/http/http-context';
import { NameSource } from '../../../shared/data/name-map';
import { Option } from '../../../shared/models/lookup';

/** Limite de ids por chamada (`UserDisplayNames.MAX_IDS`). */
export const USER_NAMES_BATCH = 100;

/** `UserDisplayNames.UserName`: só id e nome — sem e-mail, perfis ou escopo. */
export interface UserName {
  id: string;
  name: string;
}

/**
 * Nomes de exibição de usuários da assessoria para telas operacionais (autor de interação, quem
 * publicou uma política). Usa `GET /users/display-names` (qualquer usuário com `CASE_VIEW`,
 * `POLICY_VIEW` ou `USER_VIEW`), não `/users` (que exige `USER_VIEW` e traz e-mail). Use com
 * `nameMap(..., { batch: USER_NAMES_BATCH })`.
 */
@Injectable({ providedIn: 'root' })
export class UserNames implements NameSource {
  private readonly http = inject(HttpClient);

  resolve(ids: string[]): Observable<Option[]> {
    if (!ids.length) {
      return of([]);
    }
    return this.http
      .get<UserName[]>(`${API_V1}/users/display-names`, { params: { ids: ids.join(',') }, context: silentErrorContext() })
      .pipe(map((users) => users.map((u) => ({ value: u.id, label: u.name }))));
  }
}
