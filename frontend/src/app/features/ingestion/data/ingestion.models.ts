/** Contratos dos controllers do módulo ingestion (`ingestion.application.port.in`). */

export type BatchStatus =
  | 'RECEIVED'
  | 'AWAITING_FORMAT'
  | 'VALIDATING'
  | 'VALIDATED'
  | 'PROCESSING'
  | 'COMPLETED'
  | 'COMPLETED_WITH_ERRORS'
  | 'FAILED'
  | 'CANCELLED';
export type BatchSource = 'CSV' | 'XLSX' | 'API';
export type ImportMode = 'IMPORT' | 'VALIDATE_ONLY';
export type IssueSeverity = 'ERROR' | 'WARNING';

/** Situações finais (`ImportBatchStatus.isTerminal`). */
export const TERMINAL_STATUSES: readonly BatchStatus[] = ['VALIDATED', 'COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED', 'CANCELLED'];

export interface BatchCounters {
  rowsRead: number;
  rowsValid: number;
  rowsWithError: number;
  errors: number;
  warnings: number;
  debtorsCreated: number;
  debtorsUpdated: number;
  titlesCreated: number;
  titlesUpdated: number;
  titlesUnchanged: number;
  titlesSkipped: number;
  titlesSuspended: number;
  principalTotal: number;
  /** Linhas excluídas na conferência (não gravadas). */
  rowsExcluded: number;
}

/** `ImportBatchUseCases.ImportBatchView` */
export interface ImportBatchView {
  id: string;
  creditorId: string;
  portfolioId: string;
  layoutId: string | null;
  layoutCode: string;
  layoutVersion: number;
  source: BatchSource;
  mode: ImportMode;
  status: BatchStatus;
  statusReason: string | null;
  fileName: string | null;
  fileSize: number;
  fileSha256: string | null;
  forced: boolean;
  forceReason: string | null;
  duplicateOfBatchId: string | null;
  reprocessOfBatchId: string | null;
  counters: BatchCounters;
  issuesRecorded: number;
  issuesTruncated: boolean;
  totalRows: number | null;
  progressPercent: number | null;
  lastProcessedRow: number;
  cancelRequestedAt: string | null;
  requestedBy: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  /** Conferência que acompanha a prévia ou que gerou a importação. */
  reviewId: string | null;
  /** Interpretação aplicada às colunas monetárias (coluna → COMMA, DOT, PER_VALUE_*). */
  numberFormat: Record<string, FormatOption>;
}

export interface BatchFilter {
  creditorId?: string | null;
  portfolioId?: string | null;
  status?: string | null;
  source?: string | null;
}

/** `ImportIssueView` (`valueExcerpt` sempre mascarado). */
export interface ImportIssueView {
  id: string;
  rowNumber: number;
  severity: IssueSeverity;
  field: string | null;
  code: string;
  message: string;
  valueExcerpt: string | null;
}

export interface UploadRequest {
  portfolioId: string;
  layoutId: string | null;
  mode: ImportMode;
  force: boolean;
  reason: string | null;
}

// --------------------------------------------------------------------- layouts

export type LayoutFormat = 'CSV' | 'XLSX';

export interface LayoutColumn {
  field: string;
  header: string | null;
  position: number | null;
}

/** `ImportLayoutView` */
export interface ImportLayoutView {
  id: string;
  creditorId: string;
  portfolioId: string | null;
  code: string;
  version: number;
  name: string;
  format: LayoutFormat;
  delimiter: string | null;
  encoding: string | null;
  hasHeader: boolean;
  sheetName: string | null;
  columns: LayoutColumn[];
  active: boolean;
  createdAt: string;
}

export interface PublishLayoutRequest {
  creditorId: string;
  portfolioId: string | null;
  code: string;
  name: string;
  format: LayoutFormat;
  delimiter: string | null;
  encoding: string | null;
  hasHeader: boolean;
  sheetName: string | null;
  columns: LayoutColumn[];
}

export interface CanonicalField {
  field: string;
  required: boolean;
  personal: boolean;
}

/** `CanonicalLayoutView` (layout padrão `PADRAO`). */
export interface CanonicalLayoutView {
  code: string;
  version: number;
  delimiter: string;
  encoding: string;
  fields: CanonicalField[];
  reservedPrefixes: string[];
}

// --------------------------------------------------------------------- conferência (assistente)

export type ReviewStatus = 'OPEN' | 'AWAITING_APPROVAL' | 'COMMITTED' | 'REJECTED' | 'CANCELLED';
export type FormatOption = 'COMMA' | 'DOT' | 'PER_VALUE_GROUPING' | 'PER_VALUE_DECIMAL';
export type ColumnFormatStatus = 'NOT_NEEDED' | 'DETECTED' | 'INFERRED' | 'AMBIGUOUS' | 'MIXED';
export type AmountStyle = 'TYPED' | 'PLAIN' | 'COMMA_DECIMAL' | 'DOT_DECIMAL' | 'AMBIGUOUS_DOT' | 'AMBIGUOUS_COMMA' | 'INVALID';
export type AnalysisStatus = 'NOT_STARTED' | 'PENDING' | 'DONE' | 'FAILED';
export type AlertStatus = 'OPEN' | 'CONFIRMED' | 'EXCLUDED';
export type AlertDecision = 'CONFIRM' | 'EXCLUDE' | 'REOPEN';
export type AlertType =
  | 'DECIMAL_SCALE'
  | 'FILE_SCALE_SHIFT'
  | 'CENTS_PATTERN'
  | 'AMOUNT_HIGH'
  | 'BALANCE_ABOVE_PRINCIPAL'
  | 'CONTRACT_INSTALLMENTS_MISMATCH'
  | 'DEBTOR_MAGNITUDE_SPREAD'
  | 'AMOUNT_LOW'
  | 'DUE_DATE_TOO_FUTURE'
  | 'DUE_DATE_TOO_OLD';
export type TotalsMeasure = 'ROWS' | 'PRINCIPAL' | 'BALANCE' | 'CONTRACT_AMOUNT';

/** Exemplo real do arquivo convertido sob uma interpretação (`value` nulo = não lido; ver `problem`). */
export interface FormatExample {
  rowNumber: number;
  raw: string;
  style: AmountStyle;
  value: number | null;
  problem: string | null;
}

export interface FormatOptionView {
  option: FormatOption;
  total: number;
  invalidCount: number;
  examples: FormatExample[];
}

export interface ColumnFormatView {
  column: string;
  status: ColumnFormatStatus;
  requiresDecision: boolean;
  typedCount: number;
  textCount: number;
  styleCounts: Partial<Record<AmountStyle, number>>;
  decision: FormatOption | null;
  decisionSource: string | null;
  recommended: FormatOption | null;
  suggestion: FormatOption | null;
  options: FormatOptionView[];
}

export interface FormatView {
  available: boolean;
  awaitingDecision: boolean;
  confirmed: boolean;
  confirmedAt: string | null;
  confirmedBy: string | null;
  rows: number;
  typedValues: number;
  textValues: number;
  columns: ColumnFormatView[];
}

export interface MeasureView {
  measure: TotalsMeasure;
  column: string | null;
  entered: number;
  computed: number;
  difference: number;
  matches: boolean;
  scaleExponent: number | null;
  invalidCount: number;
}

export interface HintView {
  code: 'SCALE' | 'ALTERNATIVE_MATCH' | 'EQUAL_ROW' | 'INVALID_VALUES' | 'MATCHED' | 'ROWS';
  measure: TotalsMeasure | null;
  message: string;
}

export interface SuspectRow {
  rowNumber: number;
  externalId: string | null;
  amount: number;
  ambiguous: boolean;
}

export interface TotalsCheckView {
  id: string;
  batchId: string;
  matched: boolean;
  measures: MeasureView[];
  hints: HintView[];
  suspectRows: SuspectRow[];
  equalToDifference: SuspectRow[];
  createdBy: string | null;
  createdAt: string;
}

export interface TotalsStateView {
  checked: boolean;
  matched: boolean;
  checks: number;
  mismatches: number;
  lastCheck: TotalsCheckView | null;
}

export interface AlertTypeView {
  type: AlertType;
  scale: boolean;
  open: number;
  confirmed: number;
  excluded: number;
}

export interface AnalysisView {
  status: AnalysisStatus;
  total: number;
  truncated: boolean;
  open: number;
  openScale: number;
  confirmed: number;
  excluded: number;
  rowsExcluded: number;
  byType: AlertTypeView[];
  references: Record<string, unknown>;
}

export interface ImpactView {
  rows: number;
  rowsToImport: number;
  rowsWithError: number;
  rowsExcluded: number;
  newTitles: number;
  existingTitles: number;
  holdBySituation: number;
  closedBySituation: number;
  principalToImport: number;
  balanceToImport: number;
  approvalRequired: boolean;
  approvalThreshold: number | null;
  duplicateFile: boolean;
}

export interface BlockerView {
  code: string;
  message: string;
}

export interface CommitView {
  requestedBy: string | null;
  requestedAt: string | null;
  approvalRequired: boolean;
  approvalThreshold: number | null;
  forceReason: string | null;
  decidedBy: string | null;
  decidedAt: string | null;
  decisionNote: string | null;
  importBatchId: string | null;
  cancelledAt: string | null;
  cancelledBy: string | null;
  cancelReason: string | null;
}

/** `ImportReviewUseCases.ReviewView` */
export interface ReviewView {
  id: string;
  creditorId: string;
  portfolioId: string;
  status: ReviewStatus;
  currentStep: number;
  previews: number;
  batch: ImportBatchView;
  format: FormatView;
  totals: TotalsStateView;
  analysis: AnalysisView;
  impact: ImpactView | null;
  blockers: BlockerView[];
  confirmationText: string;
  commit: CommitView;
  canApprove: boolean;
  createdBy: string | null;
  createdAt: string;
}

export interface ReviewSummaryView {
  id: string;
  creditorId: string;
  portfolioId: string;
  status: ReviewStatus;
  currentStep: number;
  fileName: string | null;
  currentBatchId: string;
  batchStatus: BatchStatus | null;
  importBatchId: string | null;
  approvalRequired: boolean;
  principalToImport: number | null;
  createdBy: string | null;
  createdAt: string;
  commitRequestedBy: string | null;
  commitRequestedAt: string | null;
}

export interface AlertView {
  id: string;
  rowNumber: number;
  externalId: string | null;
  type: AlertType;
  scale: boolean;
  priority: number;
  field: string | null;
  amount: number | null;
  expectedLow: number | null;
  expectedHigh: number | null;
  reference: string | null;
  referenceSize: number | null;
  suggestedExponent: number | null;
  suggestedAmount: number | null;
  detail: string;
  status: AlertStatus;
  decidedBy: string | null;
  decidedAt: string | null;
  justification: string | null;
}

export interface BreakdownRow {
  dimension: 'AGING' | 'SITUATION' | 'PRODUCT' | 'VALIDITY';
  key: string;
  rows: number;
  principal: number;
  balance: number;
}

export interface TotalsRequest {
  rows: number | null;
  principalTotal: number | null;
  balanceTotal: number | null;
  contractAmountTotal: number | null;
}

export interface AlertDecisionRequest {
  alertIds: string[];
  allOpen: boolean;
  type: AlertType | null;
  decision: AlertDecision;
  justification: string | null;
}
