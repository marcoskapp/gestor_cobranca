import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, of } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { silentErrorContext } from '../../../core/http/http-context';
import { toHttpParams } from '../../../core/http/http-params';
import { PageRequest, PageResponse } from '../../../shared/models/page';
import type { IntegrationTokenState } from '../data/payment.models';

/** Canal do informe do pagamento (`informed_channel`; sem canal = operador da assessoria). */
export type ReportingChannel = 'INTEGRATION' | 'CREDITOR_PORTAL' | 'OPERATOR';
export type ReportedPaymentStatus = 'INFORMED' | 'CONFIRMED' | 'RECONCILED' | 'REJECTED' | 'REVERSED';
export type ImportSituation = 'SETTLED_BY_IMPORT' | 'REPORTED_PAID' | 'REPORTED_CANCELLED';

/** Filtro da aba "Pagamentos informados pelo credor" (datas `yyyy-MM-dd`, dia do pagamento). */
export interface ReportedPaymentFilter {
  from: string;
  to: string;
  creditorId: string | null;
  channel: ReportingChannel | null;
  tokenId: string | null;
  minAmount: number | null;
  maxAmount: number | null;
  status: ReportedPaymentStatus | null;
}

/** `CreditorReportedPaymentsUseCases.ReportedPaymentItem`: só o que a lista precisa; o servidor já aplicou as permissões. */
export interface ReportedPaymentItem {
  paymentId: string;
  paidAt: string;
  informedAt: string | null;
  creditorId: string;
  debtorId: string;
  /** Nulo sem `DEBTOR_VIEW`; anonimizado = nome substituto. */
  debtorName: string | null;
  debtorAnonymized: boolean;
  /** Nulo sem `AGREEMENT_VIEW` ou quando o destino são títulos. */
  agreementId: string | null;
  /** Vazio sem `DEBTOR_VIEW` e sem `CASE_VIEW`. */
  titleIds: string[];
  titleCount: number;
  amount: number;
  origin: 'CREDITOR_DIRECT' | 'CREDITOR_REPORT';
  channel: ReportingChannel;
  /** Só no canal integração e para quem tem `PUBLIC_API_TOKEN_VIEW`. */
  token: { id: string; name: string | null; prefix: string | null; revoked: boolean; found: boolean; state: IntegrationTokenState } | null;
  status: ReportedPaymentStatus;
  evidenceAvailable: boolean;
  evidenceUnverified: boolean;
}

export interface ReportedPaymentsPage {
  items: PageResponse<ReportedPaymentItem>;
  totals: { count: number; amount: number; effectiveAmount: number };
}

/** Filtro da aba "Pela carga do credor" (dia em que a carga zerou o saldo ou suspendeu o título). */
export interface ReportedTitleFilter {
  from: string;
  to: string;
  creditorId: string | null;
  importBatchId: string | null;
  situation: ImportSituation | null;
}

export interface ReportedTitleItem {
  titleId: string;
  /** Nula sem `DEBTOR_VIEW` e sem `CASE_VIEW` (e então sem link). */
  titleReference: string | null;
  creditorId: string;
  portfolioId: string;
  debtorId: string;
  debtorName: string | null;
  debtorAnonymized: boolean;
  situation: ImportSituation;
  /** Lote que zerou ou suspendeu o título (nulo sem `IMPORT_VIEW`). */
  importBatchId: string | null;
  occurredAt: string;
  amount: number;
  titleStatus: string;
}

export interface ReportedTitlesPage {
  items: PageResponse<ReportedTitleItem>;
  totals: { count: number; amount: number };
}

/** Rótulo do lote (nunca o nome do arquivo). */
export interface BatchLabel {
  id: string;
  createdAt: string;
  layoutCode: string | null;
  layoutVersion: number | null;
  source: string | null;
}

/** Máximo de ids por consulta de rótulos de lote (servidor). */
export const MAX_BATCH_LABELS = 100;

@Injectable({ providedIn: 'root' })
export class CreditorReportedApi {
  private readonly http = inject(HttpClient);

  payments(filter: ReportedPaymentFilter, page: PageRequest): Observable<ReportedPaymentsPage> {
    return this.http.get<ReportedPaymentsPage>(`${API_V1}/payment/creditor-reported`, {
      params: toHttpParams({ ...filter, page: page.page, size: page.size, sort: page.sort }),
      context: silentErrorContext(),
    });
  }

  imports(filter: ReportedTitleFilter, page: PageRequest): Observable<ReportedTitlesPage> {
    return this.http.get<ReportedTitlesPage>(`${API_V1}/titles/creditor-reported`, {
      params: toHttpParams({ ...filter, page: page.page, size: page.size, sort: page.sort }),
      context: silentErrorContext(),
    });
  }

  /** Rótulos só dos lotes visíveis (os demais ficam de fora, sem erro). Exige `IMPORT_VIEW`. */
  batchLabels(ids: readonly string[]): Observable<BatchLabel[]> {
    if (!ids.length) {
      return of([]);
    }
    return this.http.get<BatchLabel[]>(`${API_V1}/ingestion/batches/labels`, {
      params: toHttpParams({ ids: ids.slice(0, MAX_BATCH_LABELS).join(',') }),
      context: silentErrorContext(),
    });
  }
}
