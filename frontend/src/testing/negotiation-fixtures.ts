import { AgreementView, ProposalView } from '../app/features/negotiation/data/agreement.models';
import { ComponentRates, OfferView, PolicyTerms, SimulationResult } from '../app/features/negotiation/data/negotiation.models';

const rates = (principal: number, correction: number, interest: number, fine: number, fees: number, honorarium: number): ComponentRates => ({
  principal,
  correction,
  interest,
  fine,
  fees,
  honorarium,
});

/** Espelho de `StandardPolicyTerms.marketDefault()` do backend. */
export function marketTemplate(): PolicyTerms {
  return {
    base: {
      charges: {
        interestMonthlyRate: 1,
        interestMode: 'SIMPLE',
        finePercent: 2,
        correctionIndexCode: null,
        fees: [],
        honorariumPercent: 10,
        honorariumBase: 'UPDATED_DEBT',
      },
      discountTiers: [
        { upToInstallments: 1, maxDiscount: rates(20, 100, 100, 100, 100, 50) },
        { upToInstallments: 6, maxDiscount: rates(0, 60, 60, 60, 60, 20) },
        { upToInstallments: 12, maxDiscount: rates(0, 30, 30, 30, 30, 0) },
      ],
      installments: {
        maxInstallments: 12,
        minInstallmentAmount: 50,
        minDownPaymentPercent: 10,
        minDownPaymentAmount: 0,
        monthlyInterestRate: 0,
        frequency: 'MONTHLY',
        intervalDays: null,
      },
      validity: { proposalValidityDays: 5, maxDaysToFirstPayment: 10 },
      ladder: [
        { level: 0, name: 'Sem desconto', envelopeShare: rates(0, 0, 0, 0, 0, 0), maxInstallments: null },
        { level: 1, name: 'Multa e juros (metade do envelope)', envelopeShare: rates(0, 50, 50, 50, 0, 0), maxInstallments: null },
        { level: 2, name: 'Multa, juros e honorários', envelopeShare: rates(0, 100, 100, 100, 100, 100), maxInstallments: null },
        { level: 3, name: 'Limite da política (à vista)', envelopeShare: rates(100, 100, 100, 100, 100, 100), maxInstallments: 1 },
      ],
      breakRule: { overdueInstallments: 2, toleranceDays: 5 },
    },
    overrides: [],
  };
}

export function offer(overrides: Partial<OfferView> = {}): OfferView {
  return {
    level: 0,
    levelName: 'Sem desconto',
    installments: 1,
    valid: true,
    withinAuthority: true,
    discountRates: rates(0, 0, 0, 0, 0, 0),
    discount: rates(0, 0, 0, 0, 0, 0),
    discountTotal: 0,
    net: rates(1000, 0, 50, 20, 0, 107),
    negotiated: 1177,
    downPayment: null,
    total: 1177,
    financingInterest: 0,
    periodRate: null,
    schedule: [{ number: 1, dueDate: '2026-10-23', amount: 1177, interest: 0, amortization: 1177, balanceAfter: 0 }],
    discountLines: [],
    violations: [],
    ...overrides,
  };
}

export function simulationResult(overrides: Partial<SimulationResult> = {}): SimulationResult {
  return {
    creditorId: 'c-1',
    debtorId: 'd-1',
    portfolioId: 'p-1',
    policy: { policyId: 'pol-1', version: 2, overrideCode: null },
    referenceDate: '2026-10-23',
    actor: { type: 'USER', id: 'u-1', profileId: 'pr-1', profileCode: 'JUNIOR' },
    limits: { maxLevel: 1, maxDiscountShare: 50, maxInstallments: 6, maxAgreementAmount: 5000, minDownPaymentShare: 10, canFormalize: true, canApprove: false },
    requestedLevel: 0,
    grantedLevel: 0,
    levelCapped: false,
    nextLevelAvailable: true,
    justification: null,
    channel: 'PHONE',
    composition: {
      referenceDate: '2026-10-23',
      titles: [
        {
          titleId: 't-1',
          portfolioId: 'p-1',
          dueDate: '2026-09-23',
          daysOverdue: 30,
          components: rates(1000, 0, 50, 20, 0, 107),
          total: 1177,
          paidTotal: 0,
          correctionFactor: null,
          lines: [
            { titleId: 't-1', component: 'PRINCIPAL', rule: 'PRINCIPAL_BALANCE', base: null, rate: null, days: null, factor: null, amount: 1000, note: null },
            { titleId: 't-1', component: 'INTEREST', rule: 'INTEREST_SIMPLE', base: 1000, rate: 1, days: 30, factor: null, amount: 50, note: null },
          ],
        },
      ],
      total: rates(1000, 0, 50, 20, 0, 107),
      totalAmount: 1177,
      paidTotal: 0,
      maxDaysOverdue: 30,
    },
    envelope: null,
    offers: [
      offer(),
      offer({
        installments: 6,
        downPayment: 196.17,
        total: 1177,
        schedule: [
          { number: 1, dueDate: '2026-10-23', amount: 196.17, interest: 0, amortization: 196.17, balanceAfter: 980.83 },
          { number: 2, dueDate: '2026-11-23', amount: 196.17, interest: 0, amortization: 196.17, balanceAfter: 784.66 },
        ],
      }),
    ],
    excludedTitles: [],
    notes: [],
    engineVersion: 1,
    ...overrides,
  };
}

/** `ProposalView` de um operador júnior, pronta para o aceite. */
export function proposal(overrides: Partial<ProposalView> = {}): ProposalView {
  return {
    id: 'p-1',
    status: 'DRAFT',
    creditorId: 'c-1',
    debtorId: 'd-1',
    portfolioId: 'pf-1',
    caseId: 'k-1',
    simulationId: 's-1',
    policy: { policyId: 'pol-1', version: 2, overrideCode: null },
    referenceDate: '2026-10-23',
    actor: { type: 'USER', id: 'u-2', profileId: 'pr-1', profileCode: 'JUNIOR' },
    level: 1,
    justificationReason: 'PAYMENT_CAPACITY',
    copilot: false,
    channel: 'PHONE',
    titleIds: ['t-1'],
    discountRates: { principal: 0, correction: 50, interest: 50, fine: 50, fees: 0, honorarium: 0 },
    installments: 1,
    requestedDownPayment: null,
    firstDueDate: '2026-10-28',
    updatedAmount: 2953.22,
    discountAmount: 111.97,
    negotiatedAmount: 2841.25,
    downPayment: null,
    financingInterest: 0,
    totalAmount: 2841.25,
    envelopeUsage: 25,
    validUntil: '2026-10-28',
    authorityViolations: [],
    replacedAgreementId: null,
    renegotiationNumber: 0,
    handoffProposalId: null,
    decision: null,
    acceptance: null,
    agreementId: null,
    formalizedAt: null,
    createdAt: '2026-10-23T13:00:00Z',
    offer: offer({ level: 1, levelName: 'Multa e juros', total: 2841.25, schedule: [{ number: 1, dueDate: '2026-10-28', amount: 2841.25, interest: 0, amortization: 2841.25, balanceAfter: 0 }] }),
    version: 0,
    ...overrides,
  };
}

export function agreement(overrides: Partial<AgreementView> = {}): AgreementView {
  return {
    id: 'a-1',
    status: 'IN_PAYMENT',
    statusReason: null,
    proposalId: 'p-1',
    creditorId: 'c-1',
    debtorId: 'd-1',
    portfolioId: 'pf-1',
    caseId: 'k-1',
    policyId: 'pol-1',
    policyVersion: 2,
    concessionLevel: 1,
    envelopeUsage: 25,
    channel: 'PHONE',
    origin: 'HUMAN',
    participants: [{ actorType: 'USER', actorId: 'u-1', role: 'CLOSER' }],
    updatedAmount: 3000,
    discountAmount: 100,
    negotiatedAmount: 2900,
    financingInterest: 100,
    totalAmount: 3000,
    downPayment: 300,
    paidAmount: 300,
    remainingAmount: 2700,
    titleIds: ['t-1'],
    installments: [
      { id: 'i-1', number: 1, dueDate: '2026-10-25', amount: 300, paidAmount: 300, status: 'PAID', paidAt: '2026-10-25T12:00:00Z' },
      { id: 'i-2', number: 2, dueDate: '2026-11-25', amount: 1350, paidAmount: 0, status: 'OPEN', paidAt: null },
      { id: 'i-3', number: 3, dueDate: '2026-12-25', amount: 1350, paidAmount: 0, status: 'OPEN', paidAt: null },
    ],
    breakRule: { overdueInstallments: 2, toleranceDays: 5 },
    formalizedAt: '2026-10-23T14:05:00Z',
    inPaymentAt: '2026-10-25T12:00:00Z',
    settledAt: null,
    endedAt: null,
    replacedAgreementId: null,
    replacedByAgreementId: null,
    renegotiationNumber: 0,
    incrementalAmount: null,
    acceptance: null,
    termAvailable: true,
    version: 1,
    ...overrides,
  };
}
