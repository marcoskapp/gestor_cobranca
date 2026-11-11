import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map, of } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { toHttpParams } from '../../../core/http/http-params';
import { EntitySource } from '../../../shared/components/entity-picker/entity-picker';
import { Option } from '../../../shared/models/lookup';
import { PageResponse } from '../../../shared/models/page';

/** `ConversationAssigneeUseCases.AssigneeView`: só id e nome de exibição. */
export interface ConversationAssigneeView {
  id: string;
  name: string;
}

/**
 * Operadores a quem a conversa pode ser atribuída (`GET /communication/conversations/{id}/assignable-users`,
 * `INBOX_MANAGE`): a mesma regra da atribuição — vê a caixa de entrada e responde; com caso, também trabalha casos do
 * credor; sem caso, tem acesso ao credor da conversa. Não usa o cadastro de usuários (`USER_VIEW`, com e-mail).
 */
@Injectable({ providedIn: 'root' })
export class ConversationAssignees {
  private readonly http = inject(HttpClient);

  /** Fonte para `app-entity-picker`. */
  source(conversationId: string): EntitySource {
    return {
      search: (q: string) => this.find(conversationId, { q: q || null }),
      resolve: (ids: string[]) => (ids.length ? this.find(conversationId, { ids: ids.join(',') }) : of<Option[]>([])),
    };
  }

  private find(conversationId: string, extra: Record<string, string | null>): Observable<Option[]> {
    return this.http
      .get<PageResponse<ConversationAssigneeView>>(`${API_V1}/communication/conversations/${conversationId}/assignable-users`, {
        params: toHttpParams({ size: 20, ...extra }),
      })
      .pipe(map((page) => page.content.map((u) => ({ value: u.id, label: u.name }))));
  }
}
