import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map, of } from 'rxjs';
import { API_V1 } from '../../../core/config/api';
import { silentErrorContext } from '../../../core/http/http-context';
import { Amount } from './payment.models';

/** `PaymentReviewStatusUseCases.ReviewItem`: externo aguardando aprovação ou recusado (sem evidência nem observação). */
export interface ReviewItem {
  paymentId: string;
  status: 'INFORMED' | 'REJECTED';
  amount: Amount;
  paidAt: string;
  method: string | null;
  informedBy: string | null;
  informedAt: string | null;
  targetKind: 'AGREEMENT' | 'TITLES' | null;
  agreementId: string | null;
  /** Parcelas (acordo) ou títulos indicados por quem informou; vazio no acordo = em aberto, por número. */
  requestedItemIds: string[];
  reviewedBy: string | null;
  reviewedAt: string | null;
  /** Só para quem informou ou tem `PAYMENT_VIEW`. */
  rejectionReason: string | null;
  /** Há motivo, mas o perfil de quem consulta não o vê. */
  reasonRestricted: boolean;
}

/** Previsão: parte do pendente que irá para a parcela se for aprovado agora. */
export interface CoverageView {
  installmentId: string;
  paymentId: string;
  amount: Amount;
}

export interface AgreementReviewStatus {
  agreementId: string;
  pendingCount: number;
  pendingTotal: Amount;
  pending: ReviewItem[];
  rejected: ReviewItem[];
  rejectedSince: string;
  expectedCoverage: CoverageView[];
}

export interface AgreementReviewBadge {
  agreementId: string;
  pendingCount: number;
  pendingTotal: Amount;
  lastInformedAt: string | null;
  rejectedCount: number;
  lastRejectedAt: string | null;
}

export interface DebtorReviewStatus {
  creditorId: string;
  debtorId: string;
  pendingCount: number;
  pendingTotal: Amount;
  pending: ReviewItem[];
  rejected: ReviewItem[];
  rejectedSince: string;
}

/** Recusados exibidos por este período após a recusa (`app.payment.review-status.rejected-window`). */
export const REJECTED_WINDOW_DAYS = 30;

/** Limite de acordos por consulta em lote (`PaymentReviewStatusUseCases.MAX_AGREEMENTS`). */
export const REVIEW_STATUS_BATCH = 200;

/**
 * Situação de revisão dos pagamentos externos para quem acompanha acordos e casos (`AGREEMENT_VIEW`):
 * pendentes de aprovação por outra pessoa e recusados recentes. Silencioso: falhar aqui não pode
 * atrapalhar a tela que o exibe (o aviso só some).
 */
@Injectable({ providedIn: 'root' })
export class PaymentReviewStatusApi {
  private readonly http = inject(HttpClient);
  private readonly base = `${API_V1}/payment/review-status`;

  agreement(agreementId: string): Observable<AgreementReviewStatus> {
    return this.http
      .get<AgreementReviewStatus>(`${this.base}/agreements/${agreementId}`, { context: silentErrorContext() })
      .pipe(map((s) => ({ ...s, pending: s.pending ?? [], rejected: s.rejected ?? [], expectedCoverage: s.expectedCoverage ?? [] })));
  }

  /** Um resumo por acordo com pendente ou recusa recente (os demais não vêm). */
  agreements(ids: readonly string[]): Observable<AgreementReviewBadge[]> {
    const unique = [...new Set(ids)].slice(0, REVIEW_STATUS_BATCH);
    if (!unique.length) {
      return of([]);
    }
    return this.http.get<AgreementReviewBadge[]>(`${this.base}/agreements`, { params: { ids: unique.join(',') }, context: silentErrorContext() });
  }

  debtor(creditorId: string, debtorId: string): Observable<DebtorReviewStatus> {
    return this.http
      .get<DebtorReviewStatus>(`${this.base}/debtors/${debtorId}`, { params: { creditorId }, context: silentErrorContext() })
      .pipe(map((s) => ({ ...s, pending: s.pending ?? [], rejected: s.rejected ?? [] })));
  }
}

/** Parcelas cobertas pelo pendente (previsão do servidor), em ordem. */
export function coveredInstallments(status: AgreementReviewStatus | undefined | null, paymentId: string): string[] {
  return (status?.expectedCoverage ?? []).filter((c) => c.paymentId === paymentId).map((c) => c.installmentId);
}

/** Pendentes que cobrem a parcela (para o selo na linha do cronograma). */
export function pendingForInstallment(status: AgreementReviewStatus | undefined | null, installmentId: string): CoverageView[] {
  return (status?.expectedCoverage ?? []).filter((c) => c.installmentId === installmentId);
}
