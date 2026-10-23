import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { silentErrorContext } from '../../../core/http/http-context';
import { toHttpParams } from '../../../core/http/http-params';
import { PageRequest, PageResponse } from '../../../shared/models/page';
import {
  AddContactRequest,
  ContactView,
  DebtorFilter,
  DebtorName,
  DebtorSummary,
  DebtorView,
  LedgerPage,
  ObligationPartyView,
  OriginContractView,
  PrescriptionView,
  OwnershipView,
  RegisterDebtorRequest,
  RevealedContact,
  RevealedDocument,
  TitleBalanceView,
  TitleFilter,
  TitleView,
  UpdateContactRequest,
  UpdateDebtorRequest,
} from './debt.models';

/**
 * Devedores. CPF/CNPJ completo nunca vai na URL: a busca exata por documento usa
 * `POST /debtors/lookup` (a URL fica em logs de proxy/ALB e no histórico do navegador).
 */
@Injectable({ providedIn: 'root' })
export class DebtorsApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${API_V1}/debtors`;

  /** Busca por trecho do nome (o filtro `q` não deve receber documento: use {@link lookup}). */
  search(filter: DebtorFilter, page: PageRequest): Observable<PageResponse<DebtorView>> {
    return this.http.get<PageResponse<DebtorView>>(this.base, {
      params: toHttpParams({ ...filter, page: page.page, size: page.size }),
    });
  }

  /** Busca exata por documento no corpo da requisição. 404 = não encontrado (tratado pela tela). */
  lookup(document: string): Observable<DebtorView> {
    return this.http.post<DebtorView>(`${this.base}/lookup`, { document }, { context: silentErrorContext() });
  }

  /**
   * Nomes de exibição em lote (`GET /debtors/names`, até 200 ids): só id, nome e situação, para
   * telas operacionais com `CASE_VIEW`, `INBOX_VIEW` ou `DEBTOR_VIEW`; fora do escopo são omitidos.
   */
  names(ids: string[]): Observable<DebtorName[]> {
    return this.http.get<DebtorName[]>(`${this.base}/names`, { params: { ids: ids.join(',') }, context: silentErrorContext() });
  }

  get(id: string): Observable<DebtorView> {
    return this.http.get<DebtorView>(`${this.base}/${id}`);
  }

  summary(id: string): Observable<DebtorSummary> {
    return this.http.get<DebtorSummary>(`${this.base}/${id}/summary`);
  }

  /** Revelação auditada (`SENSITIVE_DATA_VIEWED`). O valor fica só em memória, na tela. */
  revealDocument(id: string): Observable<RevealedDocument> {
    return this.http.get<RevealedDocument>(`${this.base}/${id}/document`);
  }

  register(request: RegisterDebtorRequest): Observable<DebtorView> {
    return this.http.post<DebtorView>(this.base, request);
  }

  update(id: string, request: UpdateDebtorRequest): Observable<DebtorView> {
    return this.http.put<DebtorView>(`${this.base}/${id}`, request);
  }
}

@Injectable({ providedIn: 'root' })
export class DebtorContactsApi {
  private readonly http = inject(HttpClient);

  private base(debtorId: string): string {
    return `${API_V1}/debtors/${debtorId}/contacts`;
  }

  list(debtorId: string): Observable<ContactView[]> {
    return this.http.get<ContactView[]>(this.base(debtorId));
  }

  /** Revelação auditada (`SENSITIVE_DATA_VIEWED`). */
  reveal(debtorId: string, contactId: string): Observable<RevealedContact> {
    return this.http.get<RevealedContact>(`${this.base(debtorId)}/${contactId}/value`);
  }

  add(debtorId: string, request: AddContactRequest): Observable<ContactView> {
    return this.http.post<ContactView>(this.base(debtorId), request);
  }

  update(debtorId: string, contactId: string, request: UpdateContactRequest): Observable<ContactView> {
    return this.http.put<ContactView>(`${this.base(debtorId)}/${contactId}`, request);
  }

  invalidate(debtorId: string, contactId: string, reason: string): Observable<ContactView> {
    return this.http.post<ContactView>(`${this.base(debtorId)}/${contactId}/invalidate`, { reason });
  }

  validate(debtorId: string, contactId: string): Observable<ContactView> {
    return this.http.post<ContactView>(`${this.base(debtorId)}/${contactId}/validate`, null);
  }
}

@Injectable({ providedIn: 'root' })
export class TitlesApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${API_V1}/titles`;

  search(filter: TitleFilter, page: PageRequest): Observable<PageResponse<TitleView>> {
    return this.http.get<PageResponse<TitleView>>(this.base, {
      params: toHttpParams({ ...filter, page: page.page, size: page.size }),
    });
  }

  listByDebtor(debtorId: string): Observable<TitleView[]> {
    return this.http.get<TitleView[]>(`${API_V1}/debtors/${debtorId}/titles`);
  }

  get(id: string): Observable<TitleView> {
    return this.http.get<TitleView>(`${this.base}/${id}`);
  }

  balance(id: string): Observable<TitleBalanceView> {
    return this.http.get<TitleBalanceView>(`${this.base}/${id}/balance`);
  }

  ledger(id: string, page: PageRequest): Observable<LedgerPage> {
    return this.http.get<LedgerPage>(`${this.base}/${id}/ledger`, {
      params: toHttpParams({ page: page.page, size: page.size }),
    });
  }

  ownership(id: string): Observable<OwnershipView[]> {
    return this.http.get<OwnershipView[]>(`${this.base}/${id}/ownership`);
  }

  suspend(id: string, reason: string): Observable<TitleView> {
    return this.http.post<TitleView>(`${this.base}/${id}/suspend`, { reason });
  }

  reactivate(id: string): Observable<TitleView> {
    return this.http.post<TitleView>(`${this.base}/${id}/reactivate`, null);
  }

  cancel(id: string, reason: string): Observable<TitleView> {
    return this.http.post<TitleView>(`${this.base}/${id}/cancel`, { reason });
  }

  returnToCreditor(titleIds: string[], reason: string): Observable<TitleView[]> {
    return this.http.post<TitleView[]>(`${this.base}/return`, { titleIds, reason });
  }

  transfer(titleIds: string[], targetPortfolioId: string, reason: string): Observable<TitleView[]> {
    return this.http.post<TitleView[]>(`${this.base}/transfer`, { titleIds, targetPortfolioId, reason });
  }
}

/** Contratos de origem (`OriginContractController`) e suas partes, somente leitura nesta tela. */
@Injectable({ providedIn: 'root' })
export class OriginContractsApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${API_V1}/debt/contracts`;

  get(id: string): Observable<OriginContractView> {
    return this.http.get<OriginContractView>(`${this.base}/${id}`);
  }

  parties(id: string): Observable<ObligationPartyView[]> {
    return this.http.get<ObligationPartyView[]>(`${this.base}/${id}/parties`);
  }

  /** Prescrição estimada do título (`PrescriptionController`). */
  prescription(titleId: string): Observable<PrescriptionView> {
    return this.http.get<PrescriptionView>(`${API_V1}/debt/titles/${titleId}/prescription`, { context: silentErrorContext() });
  }
}
