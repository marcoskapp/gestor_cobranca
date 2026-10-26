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

/** `AgreementBreakSuspensionUseCases.BreakSuspensionView`: suspensão da quebra por pagamento em revisão. */
export interface BreakSuspensionView {
  id: string;
  agreementId: string;
  status: 'ACTIVE' | 'ENDED';
  /** Datas de negócio (yyyy-MM-dd). */
  startedOn: string;
  startedAt: string;
  /** Último dia da suspensão (prazo máximo, inclusive). */
  suspendedUntil: string;
  pendingPayments: number;
  pendingAmount: Amount;
  endReason: 'APPROVED' | 'REJECTED' | 'DEADLINE' | 'AGREEMENT_ENDED' | null;
  endedAt: string | null;
  /** Recusado: a quebra volta a valer depois deste dia. */
  graceUntil: string | null;
}

/** Parâmetros e suspensões mais recentes dos acordos envolvidos (decisões 480–489). */
export interface BreakSuspensionInfo {
  /** Prazo máximo de suspensão, em dias. */
  maxDays: number;
  /** Carência depois da recusa, em dias. */
  graceDays: number;
  suspensions: BreakSuspensionView[];
}

export interface AgreementReviewStatus {
  agreementId: string;
  pendingCount: number;
  pendingTotal: Amount;
  pending: ReviewItem[];
  rejected: ReviewItem[];
  rejectedSince: string;
  expectedCoverage: CoverageView[];
  breakSuspension: BreakSuspensionInfo | null;
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
  breakSuspension: BreakSuspensionInfo | null;
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
      .pipe(
        map((s) => ({
          ...s,
          pending: s.pending ?? [],
          rejected: s.rejected ?? [],
          expectedCoverage: s.expectedCoverage ?? [],
          breakSuspension: s.breakSuspension ?? null,
        })),
      );
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
      .pipe(map((s) => ({ ...s, pending: s.pending ?? [], rejected: s.rejected ?? [], breakSuspension: s.breakSuspension ?? null })));
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

/** Hoje no calendário do navegador (yyyy-MM-dd), para comparar com as datas de negócio da suspensão. */
export function localToday(now: Date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`;
}

/** Data de negócio (yyyy-MM-dd) no padrão brasileiro, sem conversão de fuso. */
export function formatLocalDate(value: string): string {
  const [y, m, d] = value.split('-');
  return `${d}/${m}/${y}`;
}

/**
 * Situação da quebra para os pagamentos pendentes do aviso: suspensa até a revisão (com o prazo), prazo
 * máximo já encerrado ou, sem suspensão ainda, o prazo que valerá se a regra for atingida.
 */
export type PendingBreakState =
  | { kind: 'suspended'; until: string }
  | { kind: 'deadline'; until: string }
  | { kind: 'potential'; maxDays: number }
  | { kind: 'unknown' };

export function pendingBreakState(info: BreakSuspensionInfo | null | undefined, agreementIds: readonly (string | null)[]): PendingBreakState {
  if (!info) {
    return { kind: 'unknown' };
  }
  const ids = new Set(agreementIds.filter((id): id is string => !!id));
  const mine = info.suspensions.filter((s) => ids.has(s.agreementId));
  const active = mine.filter((s) => s.status === 'ACTIVE').sort((a, b) => a.suspendedUntil.localeCompare(b.suspendedUntil));
  if (active.length) {
    return { kind: 'suspended', until: active[0].suspendedUntil };
  }
  const expired = mine.find((s) => s.endReason === 'DEADLINE');
  if (expired) {
    return { kind: 'deadline', until: expired.suspendedUntil };
  }
  return { kind: 'potential', maxDays: info.maxDays };
}

/** Recusa com carência em curso: a quebra volta a valer depois de `graceUntil` (a mais distante). */
export function rejectionGraceUntil(
  info: BreakSuspensionInfo | null | undefined,
  agreementIds: readonly (string | null)[],
  today: string = localToday(),
): string | null {
  if (!info) {
    return null;
  }
  const ids = new Set(agreementIds.filter((id): id is string => !!id));
  const graces = info.suspensions
    .filter((s) => ids.has(s.agreementId) && s.endReason === 'REJECTED' && !!s.graceUntil && s.graceUntil >= today)
    .map((s) => s.graceUntil as string)
    .sort();
  return graces.length ? graces[graces.length - 1] : null;
}

/** Selo da fila de revisão: até quando a quebra fica suspensa ou que o prazo já venceu (decisão 486). */
export interface BreakHoldBadge {
  tone: 'warning' | 'danger';
  text: string;
}

export function breakHoldBadge(s: BreakSuspensionView | null | undefined, today: string = localToday()): BreakHoldBadge | null {
  if (!s) {
    return null;
  }
  if (s.endReason === 'DEADLINE' || (s.status === 'ACTIVE' && today > s.suspendedUntil)) {
    return { tone: 'danger', text: 'Prazo de suspensão vencido: a quebra voltou a valer' };
  }
  if (s.status !== 'ACTIVE') {
    return null;
  }
  if (today === s.suspendedUntil) {
    return { tone: 'danger', text: 'Quebra suspensa só até hoje' };
  }
  return { tone: 'warning', text: `Quebra suspensa até ${formatLocalDate(s.suspendedUntil)}` };
}
