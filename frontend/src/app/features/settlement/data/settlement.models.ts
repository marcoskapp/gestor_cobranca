/**
 * Contratos do módulo settlement (`settlement.application.port.in` e records dos controllers em
 * `settlement.web`). Valores monetários e percentuais chegam como número (BigDecimal serializado).
 */

export type Amount = number;

// --------------------------------------------------------------------------- regras de comissão

export type RuleSetStatus = 'DRAFT' | 'PUBLISHED' | 'DISCARDED';
export type PaymentType = 'LUMP_SUM' | 'INSTALLMENTS';
export type ReceivedBy = 'AGENCY' | 'CREDITOR';
export type HonorariumOwnership = 'AGENCY' | 'CREDITOR';

/** `CommissionRules.RateRule`: critérios nulos não restringem. */
export interface RateRule {
  code: string;
  description: string | null;
  priority: number;
  portfolioId: string | null;
  minDaysPastDue: number | null;
  maxDaysPastDue: number | null;
  paymentType: PaymentType | null;
  receivedBy: ReceivedBy | null;
  renegotiation: boolean | null;
  rate: number;
}

export interface HonorariumPolicy {
  ownership: HonorariumOwnership;
  /** Fração dos honorários da assessoria (0–100) quando `ownership = AGENCY`. */
  agencySharePercent: number | null;
}

/** `CommissionRules` (jsonb tipado, esquema v1). */
export interface CommissionRules {
  defaultRate: number;
  rateRules: RateRule[];
  fixedFeePerAgreement: Amount | null;
  honorarium: HonorariumPolicy;
  reconciliationRequired: boolean;
}

/** `CommissionRuleUseCases.RuleSetView` */
export interface RuleSetView {
  id: string;
  creditorId: string;
  contractId: string;
  ruleVersion: number;
  status: RuleSetStatus;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  rules: CommissionRules;
  notes: string | null;
  rulesEditedBy: string | null;
  rulesEditedAt: string | null;
  publishedBy: string | null;
  publishedAt: string | null;
  version: number;
}

export interface RuleSetRequest {
  contractId: string;
  rules: CommissionRules;
  notes: string | null;
}

export interface SimulateRequest {
  ruleSetId: string | null;
  rules: CommissionRules | null;
  amount: Amount;
  honorarium: Amount | null;
  portfolioId: string | null;
  daysPastDue: number | null;
  paymentType: PaymentType | null;
  receivedBy: ReceivedBy | null;
  renegotiation: boolean;
  firstReceiptOfAgreement: boolean;
}

export interface MemoryLineView {
  step: string;
  description: string;
  base: Amount | null;
  rate: number | null;
  amount: Amount | null;
}

/** Memória de cálculo de um recebimento (ou estorno, com valores negativos). */
export interface CalculationView {
  ruleCode: string;
  rate: number;
  recovered: Amount;
  agencyHonorarium: Amount;
  creditorHonorarium: Amount;
  commissionBase: Amount;
  commission: Amount;
  fixedFee: Amount;
  transferEffect: Amount;
  memory: MemoryLineView[];
}

// --------------------------------------------------------------------------- razão (recebimentos)

export type ReceiptKind = 'PAYMENT' | 'REVERSAL';
export type RatingStatus = 'PENDING_RULES' | 'RATED';

export interface ReceiptFilter {
  creditorId: string | null;
  agreementId?: string | null;
  from: string | null;
  to: string | null;
  ratingStatus: RatingStatus | null;
  inStatement: boolean | null;
}

export interface EntryView {
  id: string;
  kind: 'ACCRUAL' | 'REVERSAL';
  ruleSetId: string | null;
  ruleVersion: number;
  reversesEntryId: string | null;
  calculation: CalculationView;
}

/** `CommissionLedgerQueries.ReceiptView` */
export interface ReceiptView {
  id: string;
  kind: ReceiptKind;
  allocationId: string;
  paymentId: string | null;
  creditorId: string;
  portfolioId: string | null;
  contractId: string | null;
  agreementId: string | null;
  titleId: string | null;
  titleReference: string | null;
  amount: Amount;
  honorarium: Amount;
  daysPastDue: number | null;
  paymentType: PaymentType | null;
  receivedBy: ReceivedBy;
  renegotiation: boolean;
  method: string | null;
  paidOn: string;
  competenceDate: string;
  ratingStatus: RatingStatus;
  pendingReason: string | null;
  reversesReceiptId: string | null;
  reversalReason: string | null;
  statementId: string | null;
  entry: EntryView | null;
}

export interface ReceiptDetailView {
  receipt: ReceiptView;
  detail: Record<string, unknown> | null;
  reversals: ReceiptView[];
}

// --------------------------------------------------------------------------- demonstrativos

export type StatementStatus = 'DRAFT' | 'CALCULATED' | 'REVIEWED' | 'APPROVED' | 'SENT' | 'ACKNOWLEDGED' | 'CONTESTED' | 'CANCELLED';
export type AdjustmentCategory = 'COMMISSION_CORRECTION' | 'PRIOR_PERIOD_CORRECTION' | 'EXPENSE_REIMBURSEMENT' | 'CONTESTATION_SETTLEMENT' | 'OTHER';
export type AdjustmentStatus = 'APPLIED' | 'PENDING_SECOND_APPROVAL' | 'REJECTED';
export type SendChannel = 'EMAIL' | 'PORTAL' | 'API' | 'LETTER' | 'OTHER';
export type ExportFormat = 'CSV' | 'XLSX' | 'PDF';

/** `SettlementStatementUseCases.TotalsView` */
export interface TotalsView {
  receivedByAgency: Amount;
  receivedByCreditor: Amount;
  reversed: Amount;
  recovered: Amount;
  reconciled: Amount;
  agencyHonorarium: Amount;
  creditorHonorarium: Amount;
  commission: Amount;
  fixedFees: Amount;
  expenses: Amount;
  adjustments: Amount;
  /** Negativo: o credor deve à assessoria. */
  netTransfer: Amount;
  transferred: Amount;
  outstanding: Amount;
  negotiated: Amount;
  forecastCommission: Amount;
  pendingReconciliation: Amount;
  pendingRules: Amount;
  openDivergences: number;
  receipts: number;
}

/** `SettlementStatementUseCases.StatementView` */
export interface StatementView {
  id: string;
  creditorId: string;
  sequenceNumber: number;
  periodStart: string;
  periodEnd: string;
  status: StatementStatus;
  totals: TotalsView | null;
  calculation: Record<string, unknown> | null;
  calculatedAt: string | null;
  calculatedBy: string | null;
  reviewedAt: string | null;
  reviewedBy: string | null;
  approvedAt: string | null;
  approvedBy: string | null;
  sentAt: string | null;
  sentBy: string | null;
  sentChannel: SendChannel | null;
  sentReference: string | null;
  creditorResponseAt: string | null;
  creditorResponseBy: string | null;
  creditorResponseNote: string | null;
  cancelledReason: string | null;
  version: number;
}

export interface StatementFilter {
  creditorId: string | null;
  status: string | null;
}

export interface PeriodSuggestion {
  creditorId: string;
  periodStart: string;
  periodEnd: string;
  settlementDay: number | null;
}

export interface CreateStatementRequest {
  creditorId: string;
  periodStart: string | null;
  periodEnd: string | null;
}

export interface SendRequest {
  channel: SendChannel;
  reference: string | null;
  exportIds: string[];
}

export interface AdjustmentRequest {
  category: AdjustmentCategory;
  /** Efeito com sinal no valor a repassar (positivo a favor do credor). */
  amount: Amount;
  reason: string;
  relatedStatementId: string | null;
}

/** `SettlementStatementUseCases.AdjustmentView` */
export interface AdjustmentView {
  id: string;
  statementId: string;
  category: AdjustmentCategory;
  amount: Amount;
  reason: string;
  relatedStatementId: string | null;
  status: AdjustmentStatus;
  requestedBy: string;
  requestedAt: string;
  decidedBy: string | null;
  decidedAt: string | null;
  decisionNote: string | null;
}

/** `SettlementStatementUseCases.ExpenseView` */
export interface ExpenseView {
  id: string;
  kind: 'CHARGE' | 'CANCELLATION';
  legalExpenseId: string;
  matterId: string | null;
  expenseType: string;
  amount: Amount;
  incurredOn: string;
  advancedBy: string | null;
  reimbursable: boolean;
  hasReceipt: boolean;
  effect: 'DEDUCT' | 'INFORMATIVE';
  competenceDate: string;
  status: 'ACTIVE' | 'CANCELLED' | 'REIMBURSED_OUTSIDE';
  statementId: string | null;
}

/** `SettlementExportUseCases.ExportView` */
export interface ExportView {
  id: string;
  statementId: string;
  format: ExportFormat;
  fileName: string;
  fileSize: number;
  sha256: string;
  rows: number;
  generatedBy: string;
  createdAt: string;
}

// --------------------------------------------------------------------------- repasses

export type TransferDirection = 'AGENCY_TO_CREDITOR' | 'CREDITOR_TO_AGENCY';

export interface TransferRequest {
  statementId: string;
  amount: Amount;
  transferredOn: string;
  bankReference: string;
  note: string | null;
}

/** `TransferUseCases.TransferView` */
export interface TransferView {
  id: string;
  statementId: string;
  creditorId: string;
  direction: TransferDirection;
  amount: Amount;
  transferredOn: string;
  bankReference: string;
  note: string | null;
  evidenceName: string | null;
  evidenceSha256: string | null;
  registeredBy: string;
  registeredAt: string;
  status: 'REGISTERED' | 'CANCELLED';
  cancelledAt: string | null;
  cancelledBy: string | null;
  cancelReason: string | null;
}

export interface StatementBalance {
  statementId: string;
  sequenceNumber: number;
  periodStart: string;
  periodEnd: string;
  status: StatementStatus;
  netTransfer: Amount;
  transferred: Amount;
  outstanding: Amount;
}

/** `TransferUseCases.BalanceView` */
export interface BalanceView {
  creditorId: string;
  toTransfer: Amount;
  toReceive: Amount;
  statements: StatementBalance[];
}

/** `SettlementPositionUseCases.DailyPoint`: acumulado até o fim do dia do período. */
export interface PositionDailyPoint {
  day: number;
  date: string;
  recovered: Amount;
  netTransfer: Amount;
}

/**
 * `SettlementPositionUseCases.PositionView`: repasse do período em aberto calculado agora como o demonstrativo
 * calcularia (simulação, nada é gravado).
 */
export interface SettlementPositionView {
  creditorId: string;
  periodStart: string;
  asOf: string;
  expectedPeriodEnd: string;
  settlementDay: number | null;
  openStatementId: string | null;
  openStatementStatus: StatementStatus | null;
  totals: TotalsView;
  direction: 'AGENCY_TO_CREDITOR' | 'CREDITOR_TO_AGENCY' | 'NONE';
  daily: PositionDailyPoint[];
  previous: {
    statementId: string;
    sequenceNumber: number;
    periodStart: string;
    periodEnd: string;
    status: StatementStatus;
    recovered: Amount;
    netTransfer: Amount;
  } | null;
  previousDaily: PositionDailyPoint[];
  closedWithBalance: StatementBalance[];
  outstandingToTransfer: Amount;
  outstandingToReceive: Amount;
  calculatedAt: string;
  cached: boolean;
  calculationMillis: number;
}
