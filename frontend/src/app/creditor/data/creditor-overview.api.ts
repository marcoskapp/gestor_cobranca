import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { silentErrorContext } from '../../core/http/http-context';
import { CREDITOR_API } from './creditor.api';
import { CreditorActivityPage, CreditorActivityScope, CreditorAwaiting, CreditorOverview, CreditorPeriod } from './creditor.models';

/** Itens por página de "Minha atividade" (o servidor aceita de 1 a 50). */
export const ACTIVITY_PAGE_SIZE = 20;

/**
 * Início e "Minha atividade" do portal do credor (fatia 7). O credor e a pessoa vêm sempre da sessão: nenhuma chamada
 * leva id de credor ou de pessoa. As telas mostram os erros no próprio cartão (sem aviso global).
 */
@Injectable({ providedIn: 'root' })
export class CreditorOverviewApi {
  private readonly http = inject(HttpClient);

  /** Indicadores do período; cada fonte fora do ar vem nula e listada em `unavailable`. */
  overview(period: CreditorPeriod): Observable<CreditorOverview> {
    return this.http.get<CreditorOverview>(`${CREDITOR_API}/overview`, {
      params: new HttpParams().set('period', period),
      context: silentErrorContext(),
    });
  }

  /** O que depende do credor agora (sem cache no servidor). */
  awaiting(): Observable<CreditorAwaiting> {
    return this.http.get<CreditorAwaiting>(`${CREDITOR_API}/awaiting`, { context: silentErrorContext() });
  }

  /**
   * Atividade da pessoa (`own`) ou do time do credor (`team`, só com `CREDITOR_ACTIVITY_VIEW`; sem ela o servidor
   * responde 403). `before` é o cursor opaco da página anterior.
   */
  activity(scope: CreditorActivityScope, before: string | null = null, size = ACTIVITY_PAGE_SIZE): Observable<CreditorActivityPage> {
    let params = new HttpParams().set('scope', scope).set('size', String(size));
    if (before) {
      params = params.set('before', before);
    }
    return this.http.get<CreditorActivityPage>(`${CREDITOR_API}/activity`, { params, context: silentErrorContext() });
  }
}
