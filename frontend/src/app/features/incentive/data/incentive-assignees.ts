import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map, of } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { toHttpParams } from '../../../core/http/http-params';
import { EntitySource } from '../../../shared/components/entity-picker/entity-picker';
import { Option } from '../../../shared/models/lookup';
import { PageResponse } from '../../../shared/models/page';

/**
 * Campo de pessoa do incentivo (`IncentiveAssignees.Purpose` no servidor): define quem é elegível — a mesma regra que o
 * servidor aplica ao gravar.
 * - `EVALUATION`: negociador avaliado na monitoria e alvo de ocorrência de conduta (`QUALITY_EVALUATE`): negociador
 *   habilitado, no escopo de quem avalia (equipe ou todos) e nunca a própria pessoa;
 * - `GOAL`: negociador da meta individual nova (`COMMISSION_PLAN_MANAGE`): negociador habilitado no escopo de quem define.
 */
export type IncentiveAssigneePurpose = 'EVALUATION' | 'GOAL';

/** `AssignableUserView`: só id e nome de exibição. */
export interface AssignableUserView {
  id: string;
  name: string;
}

const PATHS: Record<IncentiveAssigneePurpose, string> = {
  EVALUATION: `${API_V1}/incentive/quality/evaluable-users/search`,
  GOAL: `${API_V1}/incentive/goals/assignable-users`,
};

/**
 * Busca restrita de negociadores atribuíveis do incentivo: substitui o cadastro de usuários (`/users`, que exige
 * `USER_VIEW`, traz e-mail e incluía quem o servidor recusaria) nos seletores de monitoria, ocorrência e meta.
 */
@Injectable({ providedIn: 'root' })
export class IncentiveAssignees {
  private readonly http = inject(HttpClient);

  /** Fonte para `app-entity-picker`. */
  source(purpose: IncentiveAssigneePurpose): EntitySource {
    return {
      search: (q: string) => this.find(purpose, { q: q || null }),
      resolve: (ids: string[]) => (ids.length ? this.find(purpose, { ids: ids.join(',') }) : of<Option[]>([])),
    };
  }

  private find(purpose: IncentiveAssigneePurpose, extra: Record<string, string | null>): Observable<Option[]> {
    return this.http
      .get<PageResponse<AssignableUserView>>(PATHS[purpose], { params: toHttpParams({ size: 20, ...extra }) })
      .pipe(map((page) => page.content.map((u) => ({ value: u.id, label: u.name }))));
  }
}
