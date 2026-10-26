import { ReviewItem } from '../app/features/payment/data/review-status';

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
