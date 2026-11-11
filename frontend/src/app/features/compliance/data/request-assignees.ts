import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map, of } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { toHttpParams } from '../../../core/http/http-params';
import { EntitySource } from '../../../shared/components/entity-picker/entity-picker';
import { Option } from '../../../shared/models/lookup';
import { PageResponse } from '../../../shared/models/page';

/** `DataSubjectRequestAssigneeUseCases.AssigneeView`: só id e nome de exibição. */
export interface RequestAssigneeView {
  id: string;
  name: string;
}

/**
 * Responsáveis atribuíveis a uma solicitação de titular (`GET /compliance/data-subject-requests/assignable-users`,
 * `DATA_SUBJECT_REQUEST_MANAGE` e acesso a todos os credores): só quem o servidor aceita ao atribuir — usuário ativo,
 * que atende solicitações de titulares e tem acesso a todos os credores. Substitui o cadastro de usuários (`/users`,
 * que exige `USER_VIEW` e traz e-mail) no seletor de responsável.
 */
@Injectable({ providedIn: 'root' })
export class RequestAssignees implements EntitySource {
  private readonly http = inject(HttpClient);

  search(q: string): Observable<Option[]> {
    return this.find({ q: q || null });
  }

  resolve(ids: string[]): Observable<Option[]> {
    return ids.length ? this.find({ ids: ids.join(',') }) : of<Option[]>([]);
  }

  private find(extra: Record<string, string | null>): Observable<Option[]> {
    return this.http
      .get<PageResponse<RequestAssigneeView>>(`${API_V1}/compliance/data-subject-requests/assignable-users`, {
        params: toHttpParams({ size: 20, ...extra }),
      })
      .pipe(map((page) => page.content.map((u) => ({ value: u.id, label: u.name }))));
  }
}
