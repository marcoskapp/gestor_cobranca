import { AdjustmentView, CommissionRules, RuleSetView, StatementView, TotalsView } from '../app/features/settlement/data/settlement.models';

export function rules(overrides: Partial<CommissionRules> = {}): CommissionRules {
  return {
    defaultRate: 10,
    rateRules: [
      { code: 'PARC_90', description: 'Parcelado acima de 90 dias', priority: 10, portfolioId: null, minDaysPastDue: 91, maxDaysPastDue: null, paymentType: 'INSTALLMENTS', receivedBy: null, renegotiation: null, rate: 12 },
    ],
    fixedFeePerAgreement: 25,
    honorarium: { ownership: 'AGENCY', agencySharePercent: 100 },
    reconciliationRequired: true,
    ...overrides,
  };
}

export function ruleSet(overrides: Partial<RuleSetView> = {}): RuleSetView {
  return {
    id: 'rs-1',
    creditorId: 'c-1',
    contractId: 'k-1',
    ruleVersion: 2,
    status: 'DRAFT',
    effectiveFrom: null,
    effectiveTo: null,
    rules: rules(),
    notes: null,
    rulesEditedBy: 'u-2',
    rulesEditedAt: '2026-10-20T12:00:00Z',
    publishedBy: null,
    publishedAt: null,
    version: 1,
    ...overrides,
  };
}

export function totals(overrides: Partial<TotalsView> = {}): TotalsView {
  return {
    receivedByAgency: 1100,
    receivedByCreditor: 0,
    reversed: 0,
    recovered: 1100,
    reconciled: 1100,
    agencyHonorarium: 100,
    creditorHonorarium: 0,
    commission: 120,
    fixedFees: 25,
    expenses: 50,
    adjustments: -10,
    netTransfer: 795,
    transferred: 0,
    outstanding: 795,
    negotiated: 3800,
    forecastCommission: 300,
    pendingReconciliation: 0,
    pendingRules: 0,
    openDivergences: 1,
    receipts: 1,
    ...overrides,
  };
}

export function statement(overrides: Partial<StatementView> = {}): StatementView {
  return {
    id: 'st-1',
    creditorId: 'c-1',
    sequenceNumber: 3,
    periodStart: '2026-09-01',
    periodEnd: '2026-09-30',
    status: 'REVIEWED',
    totals: totals(),
    calculation: {},
    calculatedAt: '2026-10-02T12:00:00Z',
    calculatedBy: 'u-2',
    reviewedAt: '2026-10-02T13:00:00Z',
    reviewedBy: 'u-2',
    approvedAt: null,
    approvedBy: null,
    sentAt: null,
    sentBy: null,
    sentChannel: null,
    sentReference: null,
    creditorResponseAt: null,
    creditorResponseBy: null,
    creditorResponseNote: null,
    cancelledReason: null,
    version: 3,
    ...overrides,
  };
}

export function adjustment(overrides: Partial<AdjustmentView> = {}): AdjustmentView {
  return {
    id: 'adj-1',
    statementId: 'st-1',
    category: 'COMMISSION_CORRECTION',
    amount: -10,
    reason: 'Comissão em duplicidade no período anterior',
    relatedStatementId: null,
    status: 'APPLIED',
    requestedBy: 'u-3',
    requestedAt: '2026-10-02T12:30:00Z',
    decidedBy: null,
    decidedAt: null,
    decisionNote: null,
    ...overrides,
  };
}
