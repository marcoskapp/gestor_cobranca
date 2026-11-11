import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map, of } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { toHttpParams } from '../../../core/http/http-params';
import { EntitySource } from '../../../shared/components/entity-picker/entity-picker';
import { Option } from '../../../shared/models/lookup';
import { PageResponse } from '../../../shared/models/page';

/** `AgentCandidateUseCases.CandidateView`: só id e nome de exibição. */
export interface AgentCandidateView {
  id: string;
  name: string;
}

/**
 * Usuários que podem ser habilitados como operador (`GET /telephony/agents/assignable-users`, `TELEPHONY_MANAGE`): só
 * quem o servidor aceita ao gravar o ramal — usuário ativo com permissão de usar a telefonia. Substitui o cadastro de
 * usuários (`/users`, que exige `USER_VIEW` e traz e-mail) no seletor de "Habilitar operador".
 */
@Injectable({ providedIn: 'root' })
export class AgentCandidates implements EntitySource {
  private readonly http = inject(HttpClient);

  search(q: string): Observable<Option[]> {
    return this.find({ q: q || null });
  }

  resolve(ids: string[]): Observable<Option[]> {
    return ids.length ? this.find({ ids: ids.join(',') }) : of<Option[]>([]);
  }

  private find(extra: Record<string, string | null>): Observable<Option[]> {
    return this.http
      .get<PageResponse<AgentCandidateView>>(`${API_V1}/telephony/agents/assignable-users`, {
        params: toHttpParams({ size: 20, ...extra }),
      })
      .pipe(map((page) => page.content.map((u) => ({ value: u.id, label: u.name }))));
  }
}
