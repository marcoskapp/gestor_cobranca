/** Contratos de `/api/v1/analytics/...` (records do backend; valores monetários como número ou texto). */

export type Money = number | string;

/** Indicador com denominador explícito; `value` nulo quando indisponível. */
export interface Ratio {
  value: Money | null;
  numerator: Money | null;
  denominator: Money | null;
  unavailableReason: string | null;
}

export interface ReportMeta {
  from: string;
  to: string;
  groupBy: string;
  dataUntil: string | null;
  notApplicableFilters: string[];
  peopleRestricted: boolean;
  unavailableMetrics: string[];
}

export interface CountRow {
  key: string;
  label: string;
  count: number;
}

export interface DatasetFreshness {
  dataset: string;
  dataUntil: string | null;
  refreshedAt: string | null;
  durationMs: number;
  rowCount: number;
}

export interface PortfolioTotals {
  portfolioAmount: Money;
  openTitles: number;
  overdueAmount: Money;
  overdueTitles: number;
  overdueShare: Ratio;
  caseOpenBalance: Money;
  eligibleBalance: Money;
  eligibleShare: Ratio;
  openCases: number;
  eligibleCases: number;
  pendingQueueCases: number;
  pendingQueueBalance: Money;
  pendingQueueShare: Ratio;
}

export interface PortfolioRow {
  key: string;
  label: string;
  portfolioAmount: Money;
  openTitles: number;
  overdueAmount: Money;
  overdueShare: Ratio;
  eligibleBalance: Money;
  openCases: number;
  eligibleCases: number;
}

export interface CaseStatusRow {
  status: string;
  cases: number;
  openBalance: Money;
  eligibleBalance: Money;
  eligibleCases: number;
}

export interface QueueRow {
  queueId: string;
  label: string;
  cases: number;
  eligibleBalance: Money;
}

export interface PortfolioReport {
  meta: ReportMeta;
  referenceDate: string | null;
  totals: PortfolioTotals;
  byCreditor: PortfolioRow[];
  byPortfolio: PortfolioRow[];
  byDelayBucket: PortfolioRow[];
  casesByStatus: CaseStatusRow[];
  pendingQueue: QueueRow[];
}

export interface RecoveryTotals {
  recoveredGross: Money;
  reversed: Money;
  recoveredNet: Money;
  receipts: number;
  recoveryRate: Ratio;
  reversalShare: Ratio;
}

export interface RecoveryRow {
  key: string;
  label: string;
  recoveredGross: Money;
  reversed: Money;
  recoveredNet: Money;
  receipts: number;
  recoveryRate: Ratio;
}

export interface RecoveryReport {
  meta: ReportMeta;
  totals: RecoveryTotals;
  rows: RecoveryRow[];
  timeToReceipt: { agreements: number; averageDays: Money | null; medianDays: Money | null; p90Days: Money | null };
  cost: { commissionPerReal: Ratio; monthFrom: string | null; monthTo: string | null; statements: number };
}

export interface Funnel {
  effectiveContacts: number;
  proposals: number;
  agreements: number;
  firstInstallmentsPaid: number;
  contactToProposal: Ratio;
  proposalToAgreement: Ratio;
  agreementToFirstInstallment: Ratio;
  contactToAgreement: Ratio;
}

export interface BreakRate {
  windowDays: number;
  matured: number;
  broken: number;
  rate: Ratio;
}

export interface ConversionRow {
  key: string;
  label: string;
  effectiveContacts: number | null;
  proposals: number | null;
  agreements: number;
  agreedAmount: Money;
  paidAmount: Money;
  paidShare: Ratio;
  firstInstallmentsPaid: number;
  firstInstallmentRate: Ratio;
  contactToAgreement: Ratio;
}

export interface ConversionReport {
  meta: ReportMeta;
  funnel: Funnel;
  quality: {
    agreements: number;
    agreedAmount: Money;
    paidAmount: Money;
    paidShare: Ratio;
    installmentsPaid: number;
    installmentsPaidAmount: Money;
    renegotiations: number;
    renegotiationRate: Ratio;
    breaks: BreakRate[];
  };
  rows: ConversionRow[];
}

export interface ProductivityRow {
  key: string;
  label: string;
  workedCases: number;
  attempts: number;
  effectiveContacts: number;
  contactRate: Ratio;
  answeredCalls: number;
  talkSeconds: number;
  averageHandlingSeconds: Ratio;
  loggedHours: Money;
  agreements: number;
  agreementsPerLoggedHour: Ratio;
  recoveredNet: Money;
}

export interface ProductivityReport {
  meta: ReportMeta;
  total: ProductivityRow;
  rows: ProductivityRow[];
}

export interface ReliabilityReport {
  meta: ReportMeta;
  integrations: {
    paymentInstructions: number;
    paymentInstructionsFailed: number;
    instructionFailureRate: Ratio;
    instructionFailuresByCode: CountRow[];
    calls: number;
    callsFailed: number;
    callFailureRate: Ratio;
    statementImports: number;
    statementImportsFailed: number;
  };
  messages: {
    outbound: number;
    failed: number;
    failureRate: Ratio;
    failuresByCode: CountRow[];
    statementLines: number;
    duplicateStatementLines: number;
    duplicateLineRate: Ratio;
    duplicatePaymentDivergences: number;
    slaBreaches: number;
  };
  reconciliation: {
    opened: number;
    openedByType: CountRow[];
    openNow: number;
    openNowAmount: Money;
    resolved: number;
    medianResolutionHours: Money | null;
  };
  jobs: { deadNow: number; deadByType: CountRow[]; finishedInPeriod: number; deadInPeriod: number; deadRate: Ratio } | null;
  imports: {
    batches: number;
    failed: number;
    failureRate: Ratio;
    medianSeconds: Money | null;
    p95Seconds: Money | null;
    rowsRead: number;
    rowsWithError: number;
    rowErrorRate: Ratio;
  };
}

/** Filtros comuns dos painéis (estado da URL). Listas vazias = sem filtro. */
export interface AnalyticsFilter {
  from: string;
  to: string;
  creditorIds: string[];
  portfolioIds: string[];
  operatorIds: string[];
  teamIds: string[];
  channels: string[];
  groupBy: string | null;
}

export type ReportName = 'portfolio' | 'recovery' | 'conversion' | 'productivity' | 'reliability';
