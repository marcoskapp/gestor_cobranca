import {
  AdjustmentView,
  AttributionView,
  DisputeView,
  IndicatorRow,
  PeriodView,
  PlanView,
  StatementDetail,
  StatementSummary,
} from '../app/features/incentive/data/incentive.models';
import { standardRules } from '../app/features/incentive/data/plan-rules';

export function plan(overrides: Partial<PlanView> = {}): PlanView {
  return {
    id: 'p-1',
    code: 'PADRAO_MERCADO',
    version: 1,
    name: 'Padrão de mercado',
    description: null,
    status: 'PUBLISHED',
    priority: 0,
    effectiveFrom: '2026-08-01',
    effectiveTo: null,
    rules: standardRules(),
    rulesSchemaVersion: 1,
    publishedAt: '2026-08-01T10:00:00Z',
    publishedBy: 'u-9',
    ...overrides,
  };
}

export function summary(overrides: Partial<StatementSummary> = {}): StatementSummary {
  return {
    id: 'st-1',
    period: '2026-08',
    userId: 'u-x',
    userName: 'Operador X',
    status: 'CALCULATED',
    outcome: 'PAYABLE',
    planCode: 'PADRAO_MERCADO',
    planVersion: 1,
    calculatedAmount: 2494.8,
    adjustmentsAmount: 0,
    payableAmount: 2494.8,
    withheldAmount: 0,
    creditedRecovery: 100000,
    approvedAt: null,
    ...overrides,
  };
}

export function dispute(overrides: Partial<DisputeView> = {}): DisputeView {
  return {
    id: 'd-1',
    lineNumber: 1,
    ledgerEntryId: null,
    agreementId: null,
    reason: 'Linha com valor incorreto no segmento',
    status: 'OPEN',
    openedBy: 'u-x',
    openedAt: '2026-09-15T12:00:00Z',
    resolution: null,
    resolvedBy: null,
    resolvedAt: null,
    adjustmentId: null,
    ...overrides,
  };
}

export function adjustment(overrides: Partial<AdjustmentView> = {}): AdjustmentView {
  return {
    id: 'a-1',
    disputeId: null,
    amount: 800,
    reason: 'Acordo creditado a menor no período',
    status: 'PENDING_SECOND_APPROVAL',
    requestedBy: 'u-g',
    requestedAt: '2026-09-16T12:00:00Z',
    decidedBy: null,
    decidedAt: null,
    decisionNote: null,
    ...overrides,
  };
}

export function statementDetail(overrides: Partial<StatementDetail> = {}, s: Partial<StatementSummary> = {}): StatementDetail {
  return {
    summary: summary(s),
    lines: [
      { number: 1, component: 'RATE', description: 'Percentual escalonado progressivo sobre recuperado', segmentKey: 'D31_90', base: 60000, rate: 1.75, factor: 1, amount: 1050 },
      { number: 2, component: 'RATE', description: 'Percentual escalonado progressivo sobre recuperado', segmentKey: 'D361_PLUS', base: 40000, rate: 1.75, factor: 1.8, amount: 1260 },
      { number: 3, component: 'GOAL', description: 'Acelerador acima de 110%', segmentKey: null, base: 2310, rate: 125, factor: 1.2, amount: 462 },
      { number: 4, component: 'DEFLATOR_ENVELOPE', description: 'Uso do envelope 88% acima do limite de 85%', segmentKey: null, base: 2772, rate: 88, factor: 10, amount: -277.2 },
    ],
    calculationMemory: '{"plan":"PADRAO_MERCADO"}',
    disputes: [],
    adjustments: [],
    approvedBy: null,
    ...overrides,
  };
}

export function period(overrides: Partial<PeriodView> = {}): PeriodView {
  return {
    id: 'pe-1',
    period: '2026-08',
    start: '2026-08-01',
    end: '2026-08-31',
    status: 'OPEN',
    closedAt: null,
    closedBy: null,
    closeTrigger: null,
    statements: null,
    closeableFrom: '2026-09-08',
    ...overrides,
  };
}

export function indicatorRow(groupKey = 'ALL', recovered = 10000): IndicatorRow {
  return {
    groupKey,
    effort: { loggedSeconds: 36000, occupancy: 0.4, workedCases: 120, attempts: 300, effectiveContacts: 90, effectiveContactRate: 0.3, answeredCalls: 80, averageHandlingSeconds: 245, medianResponseSeconds: null, callbacksKeptRate: 0.75, handoffsReceived: null, callbacksScheduled: 20, callbacksKept: 15, callbacksMissed: 5 },
    conversion: { agreements: 12, contactToProposal: null, proposalToAgreement: null, contactToAgreement: 0.13, promisesKeptRate: 0.6, agreementsPerLoggedHour: 1.2, handoffResolution: null, promisesRegistered: 10, promisesDue: 10, promisesKept: 6, promisesBroken: 4 },
    financial: { negotiatedAmount: 50000, recoveredAmount: recovered, recoveryRate: 0.05, recoveredPerLoggedHour: 1000, averageTicket: 833, averageDiscount: 0.2, envelopeUsageAverage: 0.7, averageConcessionLevel: 1.5, averageDownPayment: 0.1, expectedRecoveryRatio: 1.1 },
    quality: { safraAgreements: 12, earlyBreak30: 0.1, earlyBreak60: 0.2, earlyBreak90: 0.25, firstInstallmentPaidRate: 0.8, renegotiationRate: 0.05, reversalRate: 0, upheldDisputes: 0, complaints: 1, complianceViolations: 0, qaScoreAverage: 8.25 },
  };
}

export function attribution(overrides: Partial<AttributionView> = {}): AttributionView {
  return {
    id: 'at-1',
    agreementId: 'ag-1',
    caseId: 'cs-1',
    creditorId: 'c-1',
    portfolioId: null,
    teamId: 't-1',
    origin: 'ASSISTANT_HANDOFF_CONFIRMED',
    channel: 'WHATSAPP',
    concessionLevel: 2,
    envelopeUsage: 60,
    delayBucket: 'D361_PLUS',
    agreementAmount: 10000,
    incrementalAmount: 10000,
    replacedAgreementId: null,
    formalizedAt: '2026-08-03T12:00:00Z',
    planId: 'p-1',
    participants: [
      { actorType: 'ASSISTANT', actorId: null, role: 'PRE_NEGOTIATOR' },
      { actorType: 'USER', actorId: 'u-x', role: 'CLOSER' },
    ],
    credits: [{ beneficiaryType: 'USER', beneficiaryId: 'u-x', role: 'CLOSER', weight: 30, renegotiationFactor: 100, reason: 'ORIGIN_WEIGHT' }],
    ...overrides,
  };
}
