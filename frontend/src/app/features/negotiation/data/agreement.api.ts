import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, throwError } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { silentErrorContext } from '../../../core/http/http-context';
import { toHttpParams } from '../../../core/http/http-params';
import { toApiError, userMessage } from '../../../core/http/problem';
import { NotificationService } from '../../../core/notification/notification.service';
import { FileDownloadService } from '../../../shared/data/file-download';
import { PageRequest, PageResponse } from '../../../shared/models/page';
import {
  AcceptRequest,
  AgreementFilter,
  AgreementPaymentView,
  AgreementView,
  CreateProposalRequest,
  ProposalFilter,
  ProposalView,
} from './agreement.models';
import { proposalRejectionMessage } from './agreement.rules';

const BASE = `${API_V1}/negotiation`;

/** Propostas: criação a partir de simulação, fila de aprovação (F16), aceite com termo e formalização. */
@Injectable({ providedIn: 'root' })
export class ProposalsApi {
  private readonly http = inject(HttpClient);
  private readonly files = inject(FileDownloadService);
  private readonly notifications = inject(NotificationService);
  private readonly base = `${BASE}/proposals`;

  create(request: CreateProposalRequest): Observable<ProposalView> {
    return this.validated(this.http.post<ProposalView>(this.base, request, { context: silentErrorContext() }));
  }

  /** Assume a sugestão transferida pelo assistente (mesmas condições, revalidadas com a sua alçada). */
  adopt(id: string, channel: string | null): Observable<ProposalView> {
    return this.http.post<ProposalView>(`${this.base}/${id}/adopt`, null, { params: toHttpParams({ channel }) });
  }

  search(filter: ProposalFilter, page: PageRequest): Observable<PageResponse<ProposalView>> {
    return this.http.get<PageResponse<ProposalView>>(this.base, { params: toHttpParams({ ...filter, page: page.page, size: page.size }) });
  }

  approvalQueue(page: PageRequest): Observable<PageResponse<ProposalView>> {
    return this.http.get<PageResponse<ProposalView>>(`${this.base}/approval-queue`, { params: toHttpParams({ page: page.page, size: page.size }) });
  }

  get(id: string): Observable<ProposalView> {
    return this.http.get<ProposalView>(`${this.base}/${id}`);
  }

  approve(id: string, note: string | null): Observable<ProposalView> {
    return this.validated(this.http.post<ProposalView>(`${this.base}/${id}/approve`, { note }, { context: silentErrorContext() }));
  }

  reject(id: string, reason: string): Observable<ProposalView> {
    return this.http.post<ProposalView>(`${this.base}/${id}/reject`, { reason });
  }

  accept(id: string, request: AcceptRequest): Observable<ProposalView> {
    return this.validated(this.http.post<ProposalView>(`${this.base}/${id}/accept`, request, { context: silentErrorContext() }));
  }

  formalize(id: string): Observable<AgreementView> {
    return this.validated(this.http.post<AgreementView>(`${this.base}/${id}/formalize`, null, { context: silentErrorContext() }));
  }

  /**
   * Comandos revalidados no servidor: a recusa por elegibilidade (ex.: `proposal.renegotiation_required`) vem traduzida;
   * os demais erros têm a mensagem padrão. O erro segue para a tela reagir.
   */
  private validated<T>(call: Observable<T>): Observable<T> {
    return call.pipe(
      catchError((error: unknown) => {
        if (error instanceof HttpErrorResponse && error.status !== 401) {
          this.notifications.error(proposalRejectionMessage(error.error) ?? userMessage(toApiError(error)));
        }
        return throwError(() => error);
      }),
    );
  }

  /** Termo aceito (PDF); o nome vem do `Content-Disposition`. */
  downloadTerm(id: string): Observable<string> {
    return this.files.download(`${this.base}/${id}/term`, `termo-proposta-${id.slice(0, 8)}.pdf`);
  }
}

/** Acordos: consulta, cronograma, pagamentos aplicados, cancelamento e termo. */
@Injectable({ providedIn: 'root' })
export class AgreementsApi {
  private readonly http = inject(HttpClient);
  private readonly files = inject(FileDownloadService);
  private readonly base = `${BASE}/agreements`;

  search(filter: AgreementFilter, page: PageRequest): Observable<PageResponse<AgreementView>> {
    return this.http.get<PageResponse<AgreementView>>(this.base, { params: toHttpParams({ ...filter, page: page.page, size: page.size }) });
  }

  get(id: string): Observable<AgreementView> {
    return this.http.get<AgreementView>(`${this.base}/${id}`);
  }

  payments(id: string): Observable<AgreementPaymentView[]> {
    return this.http.get<AgreementPaymentView[]>(`${this.base}/${id}/payments`);
  }

  cancel(id: string, reason: string): Observable<AgreementView> {
    return this.http.post<AgreementView>(`${this.base}/${id}/cancel`, { reason });
  }

  downloadTerm(id: string): Observable<string> {
    return this.files.download(`${this.base}/${id}/term`, `termo-acordo-${id.slice(0, 8)}.pdf`);
  }
}
