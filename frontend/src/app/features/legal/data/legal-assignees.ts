import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map, of } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { toHttpParams } from '../../../core/http/http-params';
import { EntitySource } from '../../../shared/components/entity-picker/entity-picker';
import { Option } from '../../../shared/models/lookup';
import { PageResponse } from '../../../shared/models/page';

/**
 * Campo de responsável do jurídico (`LegalAssigneeUseCases.Purpose`): define quem é elegível.
 * - `MATTER_RESPONSIBLE`: advogado interno do caso (sem vínculo com escritório, com acesso ao credor);
 * - `DEADLINE_RESPONSIBLE`: responsável por prazo (para o advogado parceiro, só os colegas do escritório);
 * - `FIRM_MEMBER`: advogado a vincular a um escritório parceiro (ainda sem escritório).
 */
export type AssigneePurpose = 'MATTER_RESPONSIBLE' | 'DEADLINE_RESPONSIBLE' | 'FIRM_MEMBER';

/** `LegalAssigneeUseCases.AssigneeView`: só id e nome de exibição. */
export interface AssigneeView {
  id: string;
  name: string;
}

/**
 * Busca restrita de usuários atribuíveis (`GET /legal/assignable-users`, `LEGAL_MANAGE`, decisão 686): substitui o
 * cadastro de usuários (`/users`, que exige `USER_VIEW` e traz e-mail) nos campos de responsável do jurídico.
 */
@Injectable({ providedIn: 'root' })
export class LegalAssignees {
  private readonly http = inject(HttpClient);

  /** Fonte para `app-entity-picker` e campos `entity` dos formulários. */
  source(purpose: AssigneePurpose, matterId: string | null = null): EntitySource {
    return {
      search: (q: string) => this.find(purpose, matterId, { q: q || null }),
      resolve: (ids: string[]) => (ids.length ? this.find(purpose, matterId, { ids: ids.join(',') }) : of<Option[]>([])),
    };
  }

  private find(purpose: AssigneePurpose, matterId: string | null, extra: Record<string, string | null>): Observable<Option[]> {
    return this.http
      .get<PageResponse<AssigneeView>>(`${API_V1}/legal/assignable-users`, {
        params: toHttpParams({ purpose, matterId, size: 20, ...extra }),
      })
      .pipe(map((page) => page.content.map((u) => ({ value: u.id, label: u.name }))));
  }
}
