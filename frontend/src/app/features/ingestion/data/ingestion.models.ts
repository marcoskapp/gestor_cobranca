/** Contratos dos controllers do módulo ingestion (`ingestion.application.port.in`). */

export type BatchStatus = 'RECEIVED' | 'VALIDATING' | 'VALIDATED' | 'PROCESSING' | 'COMPLETED' | 'COMPLETED_WITH_ERRORS' | 'FAILED' | 'CANCELLED';
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
