import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map, of } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { toHttpParams } from '../../../core/http/http-params';
import { EntitySource } from '../../../shared/components/entity-picker/entity-picker';
import { Option } from '../../../shared/models/lookup';
import { PageResponse } from '../../../shared/models/page';

/**
 * Campo de responsável da cobrança (`CaseAssigneeUseCases.Purpose`): define quem é elegível, pela mesma regra que o
 * servidor aplica ao gravar.
 * - `CASE_WORKER`: operador do caso ou responsável por tarefa (ativo, trabalha casos, acesso ao credor do caso);
 * - `CASE_REDISTRIBUTION_SOURCE`/`CASE_REDISTRIBUTION_TARGET`: de/para da redistribuição de casos;
 * - `DISPUTE_POOL_MEMBER`: analista a incluir no grupo de contestações do credor (vazio = grupo geral);
 * - `DISPUTE_REDISTRIBUTION_SOURCE`/`DISPUTE_REDISTRIBUTION_TARGET`: de/para da redistribuição de contestações.
 */
export type CollectionAssigneePurpose =
  | 'CASE_WORKER'
  | 'CASE_REDISTRIBUTION_SOURCE'
  | 'CASE_REDISTRIBUTION_TARGET'
  | 'DISPUTE_POOL_MEMBER'
  | 'DISPUTE_REDISTRIBUTION_SOURCE'
  | 'DISPUTE_REDISTRIBUTION_TARGET';

/** Contexto do campo: o caso, o credor do grupo ou a origem já escolhida (destinos de redistribuição). */
export interface CollectionAssigneeContext {
  caseId?: string | null;
  creditorId?: string | null;
  fromUserId?: string | null;
}

/** `CaseAssigneeUseCases.AssigneeView`: só id, nome de exibição e situação. */
export interface CollectionAssigneeView {
  id: string;
  name: string;
  active: boolean;
}

/**
 * Busca restrita de usuários atribuíveis da cobrança (`GET /collection/assignable-users`, `CASE_ASSIGN` ou
 * `DISPUTE_MANAGE`): substitui o cadastro de usuários (`/users`, que exige `USER_VIEW` e traz e-mail) nos campos de
 * responsável — a lista só traz quem o servidor aceita gravar no campo.
 */
@Injectable({ providedIn: 'root' })
export class CollectionAssignees {
  private readonly http = inject(HttpClient);

  /** Fonte para `app-entity-picker`. */
  source(purpose: CollectionAssigneePurpose, context: CollectionAssigneeContext = {}): EntitySource {
    return {
      search: (q: string) => this.find(purpose, context, { q: q || null }),
      resolve: (ids: string[]) => (ids.length ? this.find(purpose, context, { ids: ids.join(',') }) : of<Option[]>([])),
    };
  }

  private find(purpose: CollectionAssigneePurpose, context: CollectionAssigneeContext, extra: Record<string, string | null>): Observable<Option[]> {
    return this.http
      .get<PageResponse<CollectionAssigneeView>>(`${API_V1}/collection/assignable-users`, {
        params: toHttpParams({
          purpose,
          caseId: context.caseId ?? null,
          creditorId: context.creditorId ?? null,
          fromUserId: context.fromUserId ?? null,
          size: 20,
          ...extra,
        }),
      })
      .pipe(map((page) => page.content.map((u) => ({ value: u.id, label: u.active ? u.name : `${u.name} (inativo)` }))));
  }
}
