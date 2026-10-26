import { PendingWorkItem, PendingWorkSummary } from '../app/core/pending-work/pending-work.models';

/** Pendência de exemplo para testes. */
export function pendingItem(overrides: Partial<PendingWorkItem> = {}): PendingWorkItem {
  return {
    type: 'payment.external_review',
    source: 'payment',
    area: 'Pagamentos',
    label: 'Pagamentos informados para aprovar',
    hint: 'Confira o comprovante e aprove ou recuse.',
    count: 2,
    overdue: 0,
    dueToday: 0,
    severity: 'NORMAL',
    oldestAt: '2026-10-24T12:00:00Z',
    nextDueDate: null,
    route: '/pagamentos/aprovacoes',
    queryParams: {},
    ...overrides,
  };
}

export function pendingSummary(items: PendingWorkItem[] = [], overrides: Partial<PendingWorkSummary> = {}): PendingWorkSummary {
  return {
    generatedAt: '2026-10-26T15:00:00Z',
    total: items.reduce((sum, i) => sum + i.count, 0),
    overdue: items.reduce((sum, i) => sum + i.overdue, 0),
    dueToday: items.reduce((sum, i) => sum + i.dueToday, 0),
    items,
    unavailable: [],
    ...overrides,
  };
}
