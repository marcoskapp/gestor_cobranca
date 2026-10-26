/**
 * Contratos da API `/api/v1/incentive` (módulo `incentive`: remuneração variável dos negociadores).
 * Espelham os records de `incentive/application/port/in` e `domain/model/plan/PlanRules`.
 * Valores monetários e percentuais chegam como número (BigDecimal do Jackson); mês como `yyyy-MM`.
 */

/** `yyyy-MM` (YearMonth). */
export type YearMonth = string;

// ------------------------------------------------------------------ regras do plano (PlanRules)

export type DelayBucket = 'D0_30' | 'D31_90' | 'D91_180' | 'D181_360' | 'D361_PLUS' | 'UNKNOWN';
export type TierMode = 'PROGRESSIVE' | 'REACHED_TIER';
export type GoalMetric = 'RECOVERED' | 'RECOVERY_RATE' | 'RECOVERED_VS_EXPECTED';
export type GoalScope = 'INDIVIDUAL' | 'TEAM';
export type AcceleratorMode = 'TOTAL' | 'EXCESS';
export type CreditModel = 'ORIGINATOR' | 'PORTFOLIO';
export type TerminationPolicy = 'CREDIT_UNTIL_TERMINATION_PLUS_GRACE' | 'CREDIT_UNTIL_TERMINATION' | 'ALWAYS_CREDIT';
export type PoolDistribution = 'SUPERVISOR' | 'SUPERVISOR_AND_MEMBERS_EQUALLY';
export type AgreementOrigin =
  | 'HUMAN'
  | 'HUMAN_COPILOT'
  | 'ASSISTANT_HANDOFF_NEGOTIATED'
  | 'ASSISTANT_HANDOFF_CONFIRMED'
  | 'ASSISTANT_AUTONOMOUS'
  | 'PORTAL_SELF_SERVICE'
  | 'CAMPAIGN';

export interface SegmentSelector {
  creditorIds: string[];
  portfolioIds: string[];
  delayBuckets: DelayBucket[];
}

export interface Eligibility {
  roleCodes: string[];
  teamIds: string[];
  creditorIds: string[];
  portfolioIds: string[];
  minWorkedDays: number;
}

export interface RateTier {
  fromAmount: number;
  ratePercent: number;
}

export interface RateTable {
  segment: SegmentSelector;
  tiers: RateTier[];
}

export interface RecoveryRateComponent {
  mode: TierMode;
  tables: RateTable[];
}

export interface FixedPerAgreementComponent {
  amount: number;
}

export interface GoalComponent {
  metric: GoalMetric;
  scope: GoalScope;
  defaultTarget: number | null;
  triggerPercent: number;
  belowTriggerFactor: number;
  acceleratorThresholdPercent: number;
  acceleratorFactor: number;
  acceleratorMode: AcceleratorMode;
  capPercent: number | null;
}

export interface DifficultyMultiplier {
  segment: SegmentSelector;
  factor: number;
}

export interface EarlyBreakDeflator {
  windowDays: 30 | 60 | 90;
  thresholdPercent: number;
  deflatorPercent: number;
  minSample: number;
}

export interface EnvelopeDeflator {
  thresholdPercent: number;
  deflatorPercent: number;
  minSample: number;
}

export interface MonitoringGate {
  minScore: number;
  suspendWithoutEvaluation: boolean;
}

export interface QualityModifiers {
  earlyBreak: EarlyBreakDeflator | null;
  envelope: EnvelopeDeflator | null;
  monitoringGate: MonitoringGate | null;
  zeroOnSevereViolation: boolean;
}

export interface AttributionRules {
  originWeights: Record<AgreementOrigin, number>;
  influenceWindowDays: number;
  creditModel: CreditModel;
  ownRenegotiationWeightPercent: number;
  terminationPolicy: TerminationPolicy;
  terminationGraceDays: number;
}

export interface TeamPoolComponent {
  ratePercent: number;
  includeAssistantAutonomous: boolean;
  distribution: PoolDistribution;
}

export interface PlanRules {
  eligibility: Eligibility;
  recoveryRate: RecoveryRateComponent | null;
  fixedPerAgreement: FixedPerAgreementComponent | null;
  goal: GoalComponent | null;
  difficultyMultipliers: DifficultyMultiplier[];
  quality: QualityModifiers;
  attribution: AttributionRules;
  teamPool: TeamPoolComponent | null;
  limits: { periodCap: number | null };
  clearing: { graceBusinessDays: number; clawbackEnabled: boolean };
}

// ------------------------------------------------------------------ planos e metas

export type PlanStatus = 'DRAFT' | 'PUBLISHED' | 'RETIRED';

export interface PlanView {
  id: string;
  code: string;
  version: number;
  name: string;
  description: string | null;
  status: PlanStatus;
  priority: number;
  effectiveFrom: string | null;
  effectiveTo: string | null;
  rules: PlanRules;
  rulesSchemaVersion: number;
  publishedAt: string | null;
  publishedBy: string | null;
}

export interface PlanRequest {
  code: string;
  name: string;
  description: string | null;
  priority: number;
  rules: PlanRules;
}

export interface SimulatedStatement {
  userId: string;
  /** `StatementOutcome` ou `NOT_COVERED` (fora da elegibilidade da versão simulada). */
  outcome: string;
  creditedRecovery: number;
  simulatedAmount: number;
  actualAmount: number;
}

export interface SimulationView {
  planId: string;
  planCode: string;
  planVersion: number;
  period: YearMonth;
  statements: SimulatedStatement[];
  simulatedTotal: number;
  actualTotal: number;
  creditedRecovery: number;
  simulatedCostPerRecovered: number | null;
  actualCostPerRecovered: number | null;
}

export type GoalSubjectType = 'USER' | 'TEAM';
/** Unidade da meta do período: valor em reais ou percentual (decisões 387 e 500–507). */
export type GoalUnit = 'CURRENCY' | 'PERCENT';

export interface GoalView {
  id: string;
  period: YearMonth;
  subjectType: GoalSubjectType;
  subjectId: string;
  target: number;
  /** Nula só em meta antiga sem unidade dedutível: segue a métrica do plano. */
  unit: GoalUnit | null;
}

export interface GoalRequest {
  period: YearMonth;
  subjectType: GoalSubjectType;
  subjectId: string;
  target: number;
  unit: GoalUnit;
}

/** De onde vem a restrição de unidade: plano que usa a meta, sem plano, plano sem meta desse escopo, planos divergentes. */
export type GoalUnitBasis = 'PLAN' | 'NO_PLAN' | 'PLAN_WITHOUT_GOAL' | 'MIXED';

export interface GoalUnitOptions {
  units: GoalUnit[];
  basis: GoalUnitBasis;
  metric: GoalMetric | null;
  planCode: string | null;
  planVersion: number | null;
  planName: string | null;
}

// ------------------------------------------------------------------ períodos, extratos e razão

export type PeriodStatus = 'OPEN' | 'CLOSED';

export interface PeriodView {
  id: string;
  period: YearMonth;
  start: string;
  end: string;
  status: PeriodStatus;
  closedAt: string | null;
  closedBy: string | null;
  closeTrigger: string | null;
  statements: number | null;
  /** Primeiro dia em que o fechamento é permitido (fim do período + carência em dias úteis). */
  closeableFrom: string | null;
}

export type StatementStatus = 'CALCULATED' | 'DISPUTED' | 'ADJUSTED' | 'APPROVED' | 'EXPORTED';
export type StatementOutcome = 'PAYABLE' | 'NOT_ELIGIBLE' | 'NO_PLAN' | 'SUSPENDED_QUALITY_GATE';

export interface StatementSummary {
  id: string;
  period: YearMonth;
  userId: string;
  userName: string | null;
  status: StatementStatus;
  outcome: StatementOutcome;
  planCode: string | null;
  planVersion: number | null;
  calculatedAmount: number;
  adjustmentsAmount: number;
  payableAmount: number;
  withheldAmount: number;
  creditedRecovery: number;
  approvedAt: string | null;
}

export interface StatementLine {
  number: number;
  component: string;
  description: string;
  segmentKey: string | null;
  base: number | null;
  rate: number | null;
  factor: number | null;
  amount: number;
}

export type DisputeStatus = 'OPEN' | 'ACCEPTED' | 'REJECTED';

export interface DisputeView {
  id: string;
  lineNumber: number | null;
  ledgerEntryId: string | null;
  agreementId: string | null;
  reason: string;
  status: DisputeStatus;
  openedBy: string;
  openedAt: string;
  resolution: string | null;
  resolvedBy: string | null;
  resolvedAt: string | null;
  adjustmentId: string | null;
}

export type AdjustmentStatus = 'APPLIED' | 'PENDING_SECOND_APPROVAL' | 'REJECTED';

export interface AdjustmentView {
  id: string;
  disputeId: string | null;
  amount: number;
  reason: string;
  status: AdjustmentStatus;
  requestedBy: string;
  requestedAt: string;
  decidedBy: string | null;
  decidedAt: string | null;
  decisionNote: string | null;
}

export interface StatementDetail {
  summary: StatementSummary;
  lines: StatementLine[];
  calculationMemory: string | null;
  disputes: DisputeView[];
  adjustments: AdjustmentView[];
  approvedBy: string | null;
}

export interface StatementFilter {
  period: YearMonth | null;
  userId: string | null;
  status: string | null;
}

export interface DisputeRequest {
  lineNumber: number | null;
  ledgerEntryId: string | null;
  agreementId: string | null;
  reason: string;
}

export interface ResolveDisputeRequest {
  accepted: boolean;
  resolution: string;
  adjustmentAmount: number | null;
}

export type EntryKind = 'ACCRUAL' | 'REVERSAL';
export type EntryStatus = 'PENDING' | 'CLEARED' | 'WAIVED';

export interface LedgerEntryView {
  id: string;
  kind: EntryKind;
  status: EntryStatus;
  role: string | null;
  agreementId: string | null;
  paymentId: string | null;
  creditorId: string | null;
  portfolioId: string | null;
  delayBucket: DelayBucket | null;
  origin: AgreementOrigin | null;
  channel: string | null;
  baseAmount: number;
  creditWeight: number;
  renegotiationFactor: number | null;
  creditedAmount: number;
  creditNote: string | null;
  paidOn: string | null;
  clearsOn: string | null;
  period: YearMonth | null;
  statementId: string | null;
}

export interface StatementPreview {
  userId: string;
  period: YearMonth;
  planCode: string | null;
  planVersion: number | null;
  outcome: StatementOutcome | null;
  clearedRecovery: number;
  pendingRecovery: number;
  clearedAmount: number;
  projectedAmount: number;
  projectedLines: StatementLine[];
}

export type ExportFormat = 'CSV' | 'XLSX';

export interface ExportView {
  id: string;
  period: YearMonth;
  format: ExportFormat;
  fileName: string;
  size: number;
  sha256: string;
  statements: number;
  totalAmount: number;
  exportedBy: string;
  exportedAt: string;
}

// ------------------------------------------------------------------ qualidade

export interface Criterion {
  code: string;
  description: string;
  weight: number;
  critical: boolean;
}

export interface QualityForm {
  id: string;
  name: string;
  description: string | null;
  active: boolean;
  criteria: Criterion[];
}

export interface QualityFormRequest {
  name: string;
  description: string | null;
  criteria: Criterion[];
}

export type ReferenceType = 'CALL' | 'CONVERSATION' | 'CASE' | 'AGREEMENT' | 'OTHER';

export interface EvaluationView {
  id: string;
  formId: string;
  userId: string;
  evaluatorId: string;
  referenceType: ReferenceType;
  referenceId: string | null;
  evaluatedOn: string;
  score: number;
  criticalFailure: boolean;
  scores: Record<string, number>;
  comments: string | null;
  createdAt: string;
}

export interface EvaluationRequest {
  formId: string;
  userId: string;
  referenceType: ReferenceType;
  referenceId: string | null;
  evaluatedOn: string;
  scores: Record<string, number>;
  comments: string | null;
}

export type OccurrenceType = 'SEVERE_COMPLIANCE_VIOLATION' | 'COMPLIANCE_VIOLATION' | 'COMPLAINT' | 'UPHELD_DISPUTE';
export type OccurrenceStatus = 'CONFIRMED' | 'DISMISSED';

export interface OccurrenceView {
  id: string;
  userId: string;
  type: OccurrenceType;
  occurredOn: string;
  description: string;
  referenceType: ReferenceType | null;
  referenceId: string | null;
  status: OccurrenceStatus;
  registeredBy: string;
  dismissedBy: string | null;
  dismissedAt: string | null;
  dismissalReason: string | null;
}

export interface OccurrenceRequest {
  userId: string;
  type: OccurrenceType;
  occurredOn: string;
  description: string;
  referenceType: ReferenceType | null;
  referenceId: string | null;
}

export interface PersonRangeFilter {
  userId: string | null;
  from: string | null;
  to: string | null;
}

// ------------------------------------------------------------------ desempenho e atribuição

export type PerformanceGroupBy = 'NONE' | 'USER' | 'TEAM' | 'CREDITOR' | 'PORTFOLIO' | 'DELAY_BUCKET' | 'CHANNEL';

export interface PerformanceQuery {
  from: string;
  to: string;
  userId: string | null;
  teamId: string | null;
  creditorId: string | null;
  portfolioId: string | null;
  delayBucket: string | null;
  channel: string | null;
  groupBy: PerformanceGroupBy;
}

/** Taxas são frações (0,25 = 25%); tempos em segundos; valores em reais. */
export interface Effort {
  loggedSeconds: number | null;
  occupancy: number | null;
  workedCases: number;
  attempts: number;
  effectiveContacts: number;
  effectiveContactRate: number | null;
  answeredCalls: number;
  averageHandlingSeconds: number | null;
  medianResponseSeconds: number | null;
  callbacksKeptRate: number | null;
  handoffsReceived: number | null;
  callbacksScheduled: number;
  callbacksKept: number;
  callbacksMissed: number;
}

export interface Conversion {
  agreements: number;
  contactToProposal: number | null;
  proposalToAgreement: number | null;
  contactToAgreement: number | null;
  promisesKeptRate: number | null;
  agreementsPerLoggedHour: number | null;
  handoffResolution: number | null;
  promisesRegistered: number;
  promisesDue: number;
  promisesKept: number;
  promisesBroken: number;
}

export interface Financial {
  negotiatedAmount: number | null;
  recoveredAmount: number | null;
  recoveryRate: number | null;
  recoveredPerLoggedHour: number | null;
  averageTicket: number | null;
  averageDiscount: number | null;
  envelopeUsageAverage: number | null;
  averageConcessionLevel: number | null;
  averageDownPayment: number | null;
  expectedRecoveryRatio: number | null;
}

export interface QualityIndicators {
  safraAgreements: number;
  earlyBreak30: number | null;
  earlyBreak60: number | null;
  earlyBreak90: number | null;
  firstInstallmentPaidRate: number | null;
  renegotiationRate: number | null;
  reversalRate: number | null;
  upheldDisputes: number | null;
  complaints: number | null;
  complianceViolations: number | null;
  qaScoreAverage: number | null;
}

export interface IndicatorRow {
  groupKey: string;
  effort: Effort;
  conversion: Conversion;
  financial: Financial;
  quality: QualityIndicators;
}

export interface IndicatorReport {
  from: string;
  to: string;
  groupBy: PerformanceGroupBy;
  rows: IndicatorRow[];
  unavailableMetrics: string[];
}

export interface AttributionParticipant {
  actorType: 'USER' | 'ASSISTANT' | 'DEBTOR' | 'SYSTEM';
  actorId: string | null;
  role: string;
}

export interface AttributionCredit {
  beneficiaryType: 'USER' | 'TEAM';
  beneficiaryId: string;
  role: string;
  weight: number;
  renegotiationFactor: number | null;
  reason: string | null;
}

export interface AttributionView {
  id: string;
  agreementId: string;
  caseId: string | null;
  creditorId: string;
  portfolioId: string | null;
  teamId: string | null;
  origin: AgreementOrigin;
  channel: string | null;
  concessionLevel: number | null;
  envelopeUsage: number | null;
  delayBucket: DelayBucket | null;
  agreementAmount: number;
  incrementalAmount: number;
  replacedAgreementId: string | null;
  formalizedAt: string;
  planId: string | null;
  participants: AttributionParticipant[];
  credits: AttributionCredit[];
}
