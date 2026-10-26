import { BreakSuspensionInfo, BreakSuspensionView, ReviewItem } from '../app/features/payment/data/review-status';

/** Pagamento externo aguardando aprovação (padrão) ou recusado, como devolve `/payment/review-status`. */
export function reviewItem(overrides: Partial<ReviewItem> = {}): ReviewItem {
  return {
    paymentId: 'pg-1',
    status: 'INFORMED',
    amount: 1350,
    paidAt: '2026-10-24T15:00:00Z',
    method: 'TRANSFER',
    informedBy: 'u-2',
    informedAt: '2026-10-25T13:30:00Z',
    targetKind: 'AGREEMENT',
    agreementId: 'a-1',
    requestedItemIds: ['i-2'],
    reviewedBy: null,
    reviewedAt: null,
    rejectionReason: null,
    reasonRestricted: false,
    ...overrides,
  };
}

/** Suspensão da quebra em vigor (padrão) ou encerrada, como vem em `breakSuspension.suspensions`. */
export function breakSuspension(overrides: Partial<BreakSuspensionView> = {}): BreakSuspensionView {
  return {
    id: 'bs-1',
    agreementId: 'a-1',
    status: 'ACTIVE',
    startedOn: '2026-10-26',
    startedAt: '2026-10-26T03:20:00Z',
    suspendedUntil: '2026-11-02',
    pendingPayments: 1,
    pendingAmount: 1350,
    endReason: null,
    endedAt: null,
    graceUntil: null,
    ...overrides,
  };
}

/** `breakSuspension` do resumo de revisão: prazo máximo 7 dias, carência 3. */
export function breakSuspensionInfo(suspensions: BreakSuspensionView[] = []): BreakSuspensionInfo {
  return { maxDays: 7, graceDays: 3, suspensions };
}
