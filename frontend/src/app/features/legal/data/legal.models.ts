/** Contratos dos controllers do módulo legal (`/api/v1/legal`). Enums como texto, conforme o backend. */

export type Amount = number;

export type LegalStage =
  | 'ELIGIBLE'
  | 'UNDER_REVIEW'
  | 'AWAITING_AUTHORIZATION'
  | 'EXTRAJUDICIAL'
  | 'FILED'
  | 'IN_PROGRESS'
  | 'ENFORCEMENT'
  | 'SETTLED_IN_COURT'
  | 'CLOSED'
  | 'NOT_VIABLE'
  | 'RETURNED_TO_CREDITOR';

export type LegalRoute =
  | 'PROTEST'
  | 'CREDIT_BUREAU_LISTING'
  | 'EXTRAJUDICIAL_COLLATERAL_ENFORCEMENT'
  | 'EXECUTION'
  | 'MONITORY_ACTION'
  | 'COLLECTION_ACTION'
  | 'SEARCH_AND_SEIZURE';

export type ResponsibleType = 'NONE' | 'INTERNAL' | 'LAW_FIRM';

// --------------------------------------------------------------------- caso jurídico

export interface MatterView {
  id: string;
  collectionCaseId: string;
  debtorId: string;
  creditorId: string;
  portfolioId: string;
  origin: 'RULE' | 'PRESCRIPTION_ALERT' | 'MANUAL';
  eligibilityRuleId: string | null;
  referralNote: string | null;
  titleIds: string[];
  flags: string[];
  stage: LegalStage;
  resumeStage: LegalStage | null;
  stageReason: string | null;
  stageChangedAt: string;
  nextStages: LegalStage[];
  primaryRoute: LegalRoute | null;
  responsibleType: ResponsibleType;
  responsibleUserId: string | null;
  lawFirmId: string | null;
  debtAmount: Amount;
  expectedCosts: Amount | null;
  estimatedRecoverable: Amount | null;
  authorizationId: string | null;
  authorizedBudget: Amount | null;
  expensesTotal: Amount;
  recoveredAmount: Amount;
  agreementId: string | null;
  collectionHold: boolean;
  referredAt: string;
  closedAt: string | null;
  closureReason: string | null;
  version: number;
}

export interface DebtorInfo {
  id: string;
  name: string;
  maskedDocument: string;
  personType: string;
}

export interface TitleInfo {
  titleId: string;
  externalId: string;
  installmentNumber: number | null;
  dueDate: string;
  openBalance: Amount;
  status: string;
  daysOverdue: number;
}

export interface StageEntryView {
  id: string;
  fromStage: LegalStage | null;
  toStage: LegalStage;
  reason: string | null;
  closureReason: string | null;
  cause: string | null;
  occurredAt: string;
  actorId: string | null;
}

export interface MatterSheet {
  matter: MatterView;
  debtor: DebtorInfo | null;
  titles: TitleInfo[];
  viability: AssessmentView | null;
  authorizations: AuthorizationView[];
  measures: MeasureView[];
  lawsuits: LawsuitView[];
  deadlines: DeadlineView[];
  expenses: ExpenseView[];
  documents: DocumentView[];
  history: StageEntryView[];
}

export interface StageCount {
  stage: LegalStage;
  matters: number;
  debtAmount: Amount;
  expensesTotal: Amount;
  recoveredAmount: Amount;
}

export interface LegalDashboard {
  stages: StageCount[];
  openMatters: number;
  openDebtAmount: Amount;
  expensesTotal: Amount;
  recoveredAmount: Amount;
  deadlinesDueSoon: number;
  deadlinesMissed: number;
}

export interface MatterFilter {
  creditorId?: string | null;
  debtorId?: string | null;
  caseId?: string | null;
  /** Fases separadas por vírgula. */
  stage?: string | null;
  responsibleType?: string | null;
  lawFirmId?: string | null;
  open?: boolean | null;
}

export interface ReferRequest {
  collectionCaseId: string;
  titleIds: string[] | null;
  reason: string;
}

export interface TransitionRequest {
  stage: LegalStage;
  reason: string;
  closureReason: string | null;
  agreementId: string | null;
}

export interface AssignRequest {
  responsibleType: ResponsibleType;
  userId: string | null;
  lawFirmId: string | null;
}

export interface AgreementLinkView {
  agreementId: string;
  status: string;
  judicial: boolean;
  broken: boolean;
  replacedAgreementId: string | null;
  lastEventAt: string;
}

// --------------------------------------------------------------------- viabilidade

export interface ChecklistEntry {
  code: string;
  label: string;
  status: 'PRESENT' | 'MISSING' | 'NOT_APPLICABLE';
  required: boolean;
}

export interface AssessmentInput {
  otherAssetsValue: Amount | null;
  realizationRate: number | null;
  successProbability: number | null;
  expectedCosts: Amount | null;
}

export interface CollateralFactView {
  collateralId: string;
  type: string;
  status: string;
  estimatedValue: Amount | null;
  lastAppraisalDate: string | null;
}

export interface PrescriptionFactView {
  titleId: string;
  executiveTitle: boolean;
  executionEstimatedDate: string | null;
  executionPrescribed: boolean;
  collectionEstimatedDate: string | null;
  collectionPrescribed: boolean;
  ruleValidated: boolean;
}

export interface ViabilityResultView {
  debtAmount: Amount;
  collectibleDebt: Amount;
  collateralValue: Amount;
  collateralRecoverable: Amount;
  otherAssetsRecoverable: Amount;
  estimatedRecoverable: Amount;
  expectedCosts: Amount;
  netBenefit: Amount;
  costBenefitRatio: number | null;
  economicallyViable: boolean;
  availableRoutes: LegalRoute[];
  suggestedRoutes: LegalRoute[];
  flags: string[];
  collaterals: CollateralFactView[];
  prescriptions: PrescriptionFactView[];
  suggestedChecklist: ChecklistEntry[];
}

export interface AssessmentRequest {
  input: AssessmentInput;
  viable: boolean;
  recommendedRoutes: LegalRoute[];
  checklist: ChecklistEntry[];
  assetSearchNotes: string | null;
  opinion: string | null;
}

export interface AssessmentView {
  id: string;
  matterId: string;
  debtAmount: Amount;
  collectibleDebt: Amount;
  collateralValue: Amount;
  collateralRecoverable: Amount;
  otherAssetsValue: Amount | null;
  realizationRate: number;
  successProbability: number;
  expectedCosts: Amount;
  estimatedRecoverable: Amount;
  netBenefit: Amount;
  costBenefitRatio: number | null;
  economicallyViable: boolean;
  viable: boolean;
  recommendedRoutes: LegalRoute[];
  availableRoutes: LegalRoute[];
  suggestedRoutes: LegalRoute[];
  flags: string[];
  checklist: ChecklistEntry[];
  assetSearchNotes: string | null;
  opinion: string | null;
  assessedAt: string;
  assessedBy: string | null;
}

// --------------------------------------------------------------------- autorização do credor

export interface AuthorizationView {
  id: string;
  matterId: string;
  status: 'REQUESTED' | 'GRANTED' | 'DENIED' | 'SUPERSEDED';
  requestedRoutes: LegalRoute[];
  requestedBudget: Amount;
  requestNote: string | null;
  authorizedRoutes: LegalRoute[];
  approvedBudget: Amount | null;
  decisionDate: string | null;
  channel: string | null;
  creditorRepresentative: string | null;
  evidenceDocumentId: string | null;
  decisionNote: string | null;
  requestedAt: string;
  requestedBy: string | null;
  decidedAt: string | null;
  decidedBy: string | null;
}

export interface AuthorizationRequest {
  routes: LegalRoute[];
  budget: Amount;
  note: string | null;
}

export interface AuthorizationDecisionRequest {
  decision: 'GRANTED' | 'DENIED';
  routes: LegalRoute[] | null;
  approvedBudget: Amount | null;
  decisionDate: string;
  channel: string;
  representative: string;
  evidenceDocumentId: string | null;
  note: string | null;
}

// --------------------------------------------------------------------- medidas, processos, prazos

export interface MeasureView {
  id: string;
  matterId: string;
  type: 'NOTICE' | 'PROTEST' | 'CREDIT_BUREAU_LISTING' | 'COLLATERAL_ENFORCEMENT';
  titleIds: string[];
  collateralId: string | null;
  amount: Amount | null;
  authority: string | null;
  protocol: string | null;
  priorNoticeOn: string | null;
  submittedOn: string | null;
  effectiveOn: string | null;
  resolvedOn: string | null;
  dueDate: string | null;
  status: string;
  statusReason: string | null;
  provider: string | null;
  providerReference: string | null;
  createdAt: string;
}

export interface LawsuitView {
  id: string;
  matterId: string;
  cnjNumber: string;
  cnjFormatted: string;
  courtCode: string;
  courtName: string | null;
  courtUnit: string | null;
  route: LegalRoute;
  actionClass: string | null;
  creditorPole: 'ACTIVE' | 'PASSIVE';
  debtorIds: string[];
  claimAmount: Amount | null;
  filedOn: string;
  status: string;
  statusReason: string | null;
  provider: string | null;
  lastSyncedAt: string | null;
  version: number;
}

export interface MovementView {
  id: string;
  lawsuitId: string;
  occurredOn: string;
  type: string;
  description: string | null;
  source: 'MANUAL' | 'PROVIDER';
  provider: string | null;
  externalId: string | null;
  amount: Amount | null;
  collateralId: string | null;
  recordedAt: string;
  recordedBy: string | null;
}

export interface MovementResult {
  movement: MovementView;
  deadline: DeadlineView | null;
  matter: MatterView;
  prescriptionInterruptions: number;
}

export interface DeadlineView {
  id: string;
  matterId: string;
  lawsuitId: string | null;
  movementId: string | null;
  measureId: string | null;
  kind: string;
  description: string;
  courtCode: string | null;
  startDate: string | null;
  startEvent: string | null;
  termDays: number | null;
  countingMode: string | null;
  fixedDate: string | null;
  dueDate: string;
  responsibleUserId: string | null;
  status: 'PENDING' | 'MET' | 'MISSED' | 'CANCELLED';
  completedOn: string | null;
  statusReason: string | null;
  lastAlertLevel: number | null;
  businessDaysRemaining: number | null;
  createdAt: string;
  version: number;
}

export interface DeadlineFilter {
  matterId?: string | null;
  status?: string | null;
  dueFrom?: string | null;
  dueTo?: string | null;
  mine?: boolean | null;
}

export interface HearingView {
  id: string;
  lawsuitId: string;
  matterId: string;
  type: string;
  scheduledAt: string;
  mode: 'IN_PERSON' | 'VIDEO' | 'HYBRID';
  location: string | null;
  link: string | null;
  participantUserIds: string[];
  externalParticipants: string | null;
  status: 'SCHEDULED' | 'HELD' | 'CANCELLED';
  outcome: string | null;
  resultNotes: string | null;
  rescheduleCount: number;
  version: number;
}

export interface ExpenseView {
  id: string;
  matterId: string;
  lawsuitId: string | null;
  type: string;
  description: string | null;
  amount: Amount;
  incurredOn: string;
  advancedBy: string;
  lawFirmId: string | null;
  reimbursable: boolean;
  receiptDocumentId: string | null;
  overBudget: boolean;
  status: 'RECORDED' | 'REIMBURSED' | 'CANCELLED';
  statusDate: string | null;
  statusReason: string | null;
  recordedAt: string;
  recordedBy: string | null;
}

export interface DocumentView {
  id: string;
  matterId: string;
  kind: string;
  linkType: string | null;
  linkId: string | null;
  description: string | null;
  fileName: string;
  contentType: string;
  size: number;
  sha256: string;
  uploadedAt: string;
  uploadedBy: string | null;
}

export interface DocumentRequest {
  kind: string;
  linkType: string | null;
  linkId: string | null;
  description: string | null;
}

// --------------------------------------------------------------------- regras, escritórios, parâmetros

export interface RuleView {
  id: string;
  creditorId: string;
  portfolioId: string | null;
  name: string;
  active: boolean;
  minDaysOverdue: number | null;
  minOpenBalance: Amount | null;
  requireCollateral: boolean;
  requireExecutiveTitle: boolean;
  minBrokenAgreements: number | null;
  prescriptionWithinDays: number | null;
  version: number;
}

export interface RuleRequest {
  creditorId: string;
  portfolioId: string | null;
  name: string;
  minDaysOverdue: number | null;
  minOpenBalance: Amount | null;
  requireCollateral: boolean;
  requireExecutiveTitle: boolean;
  minBrokenAgreements: number | null;
  prescriptionWithinDays: number | null;
}

export interface LawFirmView {
  id: string;
  name: string;
  maskedCnpj: string | null;
  cnpj: string | null;
  oabRegistration: string | null;
  contactEmail: string | null;
  active: boolean;
  version: number;
}

export interface LawFirmRequest {
  name: string;
  cnpj: string | null;
  oabRegistration: string | null;
  contactEmail: string | null;
}

export interface MemberView {
  lawFirmId: string;
  userId: string;
  userName: string;
  active: boolean;
  joinedAt: string;
  leftAt: string | null;
}

export interface LegalSettings {
  conductModel: 'INTERNAL_ONLY' | 'LAW_FIRMS_ONLY' | 'BOTH';
  allowedExpenseAdvancers: string[];
  budgetEnforcement: 'BLOCK' | 'WARN';
  prescribedTitlePolicy: 'EXCLUDE' | 'FLAG';
  collectionHoldStage: 'REVIEW' | 'AUTHORIZATION';
  deadlineAlertLevels: number[];
  yearEndRecess: boolean;
  removalDeadlineBusinessDays: number;
  homologationDeadlineBusinessDays: number;
  creditBureauNoticeDays: number;
  prescriptionWarningDays: number;
  referralCooldownDays: number;
  customized?: boolean;
}

export interface SuspensionView {
  id: string;
  startDate: string;
  endDate: string;
  courtCode: string | null;
  reason: string;
  createdAt: string;
}
