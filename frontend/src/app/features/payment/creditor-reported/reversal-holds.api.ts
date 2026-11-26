import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { silentErrorContext } from '../../../core/http/http-context';
import { toHttpParams } from '../../../core/http/http-params';
import { PageRequest, PageResponse } from '../../../shared/models/page';
import type { IntegrationTokenState } from '../data/payment.models';
import type { ReportingChannel } from './creditor-reported.api';

export type HoldState = 'PENDING' | 'RELEASED' | 'ENDED';
export type HoldReleaseReason = 'CREDITOR_CONFIRMED_NOT_RECEIVED' | 'PAYMENT_ON_WRONG_TITLE' | 'OTHER';

/**
 * Filtro da aba "Estornos em conferência" (dia do estorno, `yyyy-MM-dd`). Os que aguardam conferência vêm todos, sem
 * período; o pagamento é só condição dentro do escopo (atalho da ficha do pagamento).
 */
export interface HoldFilter {
  from: string | null;
  to: string | null;
  creditorId: string | null;
  state: HoldState | null;
  paymentId: string | null;
}

/**
 * `ReversalHoldUseCases.HoldView`: o servidor já aplicou as permissões (nome do devedor só com `DEBTOR_VIEW`, títulos com
 * `DEBTOR_VIEW` ou `CASE_VIEW`, acordo com `AGREEMENT_VIEW`). Nunca traz justificativa do estorno nem da liberação.
 */
export interface HoldView {
  id: string;
  version: number;
  creditorId: string;
  debtorId: string;
  debtorName: string | null;
  debtorAnonymized: boolean;
  paymentId: string;
  reversalId: string;
  /** Acordos afetados (vazio sem `AGREEMENT_VIEW`; pode haver mais de um, com sobra realocada). */
  agreementIds: string[];
  titleIds: string[];
  titleCount: number;
  installmentCount: number;
  reversalReason: string;
  state: HoldState;
  placedAt: string;
  placedByType: 'USER' | 'SYSTEM';
  placedBy: string | null;
  releasedAt: string | null;
  releasedBy: string | null;
  releaseReason: HoldReleaseReason | null;
  endedAt: string | null;
  /** `PAYMENT_REVIEW`, não estornou nem informou e a retenção está pendente. */
  canRelease: boolean;
  reversalsInWindow: number;
}

/** `PaymentReversalHoldsUseCases.HeldReversalItem`. */
export interface HeldReversalItem {
  hold: HoldView;
  amount: number | null;
  paidAt: string | null;
  channel: ReportingChannel | null;
  /** Só no canal integração e com `PUBLIC_API_TOKEN_VIEW`. */
  token: { id: string; name: string | null; prefix: string | null; revoked: boolean; found: boolean; state: IntegrationTokenState } | null;
  reversalSource: 'MANUAL' | 'PROVIDER' | null;
  reversedAt: string | null;
}

export interface ReleaseRequest {
  version: number;
  reasonCode: HoldReleaseReason;
  note: string;
  confirmedWithCreditor: boolean;
}

/** Título retido (selo "Estorno em conferência"); `paymentId` só com `PAYMENT_VIEW`. */
export interface HeldTitle {
  titleId: string;
  holdId: string;
  paymentId: string | null;
  placedAt: string;
}

@Injectable({ providedIn: 'root' })
export class ReversalHoldsApi {
  private readonly http = inject(HttpClient);

  search(filter: HoldFilter, page: PageRequest): Observable<PageResponse<HeldReversalItem>> {
    return this.http.get<PageResponse<HeldReversalItem>>(`${API_V1}/payment/reversal-holds`, {
      params: toHttpParams({ ...filter, page: page.page, size: page.size }),
      context: silentErrorContext(),
    });
  }

  forPayment(paymentId: string): Observable<HeldReversalItem[]> {
    return this.http.get<HeldReversalItem[]>(`${API_V1}/payment/reversal-holds/by-payment/${paymentId}`, {
      context: silentErrorContext(),
    });
  }

  release(holdId: string, body: ReleaseRequest): Observable<HeldReversalItem> {
    return this.http.post<HeldReversalItem>(`${API_V1}/payment/reversal-holds/${holdId}/release`, body, {
      context: silentErrorContext(),
    });
  }

  /** Títulos retidos do devedor (selo). Exige `DEBTOR_VIEW` ou `CASE_VIEW`. */
  heldTitles(debtorId: string): Observable<HeldTitle[]> {
    return this.http.get<HeldTitle[]>(`${API_V1}/titles/reversal-holds`, {
      params: toHttpParams({ debtorId }),
      context: silentErrorContext(),
    });
  }
}
